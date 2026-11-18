package com.aim2day.goal.goals.trash;

import com.aim2day.goal.common.lock.UserRowLock;
import com.aim2day.goal.common.time.UtcTime;
import com.aim2day.goal.common.tx.Tx;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 30일이 지난 삭제 목표의 영구 정리(ERD §4.4, API 명세 §4.7).
 *
 * 세 인스턴스가 같은 시각에 이 작업을 돌려도 같은 결과가 나와야 한다. 같은 사용자의 정리는 users 행 잠금으로 직렬화되고,
 * 같은 root를 여러 인스턴스가 차례로 처리해도 결과가 같다(멱등). 그래서 정리 대상("root" = 기한이 지난 직접 삭제
 * 목표)마다 별도 트랜잭션을 열고 다음 순서로 처리한다.
 *   1. 소유자 users 행 FOR UPDATE. 다른 쓰기 경로(삭제·복원·행동·좌표)와 같은 첫 잠금이라 같은 사용자의 쓰기와 직렬화된다.
 *      먼저 온 인스턴스가 users를 쥐면 나머지는 여기서 기다리다 커밋 뒤에 풀린다.
 *   2. root 행 FOR UPDATE SKIP LOCKED로 다시 읽는다. 아직 "기한이 지난 삭제 상태"인지 잠근 뒤에 다시 확인하는 것이다.
 *      이미 다른 인스턴스가 정리해 행이 없거나, 복원돼 deleted_at이 없으면 빈 결과라 건너뛴다. 다른 트랜잭션이 행을
 *      쥐고 있어도 기다리지 않고 건너뛰어(SKIP LOCKED) 다음 회차에 맡긴다.
 *   3. ERD §4.4 순서로 hard-delete: 미완료 행동 -> 주간 -> 월간 -> 연간. 각 단계는 "완료 기록이 남았는지"로 보존을 판단한다.
 *
 * 처리 경로는 root와 그 모든 자손이다. 자손이 따로 삭제돼 자신의 기한이 남아 있어도 root가 정리되면 복원할 길이 없으므로
 * 함께 처리한다. 보존된 행은 deleted_at을 그대로 두며, 다음 회차에 root로 다시 잡혀도 결과가 같다(멱등).
 * 상위부터(연간 -> 월간 -> 주간) 처리해 상위 root가 하위 root를 함께 정리하게 하고, 이미 사라진 하위 root는 2번에서 건너뛴다.
 *
 * 쿼리는 모두 네이티브 SQL이다. DELETE ... WHERE 안에서 같은 테이블을 서브쿼리로 읽는 문장은 MySQL이 거부하므로(오류 1093)
 * 대상 id를 먼저 읽어 IN 목록으로 지운다. 행동 좌표(action_quadrant_placements)는 FK ON DELETE CASCADE로 함께 지워진다.
 */
@Service
public class TrashPurgeService {

	private static final Logger log = LoggerFactory.getLogger(TrashPurgeService.class);

	@PersistenceContext
	private EntityManager em;

	private final UserRowLock userRowLock;
	private final Clock clock;
	private final TransactionTemplate tx;
	private final TransactionTemplate readTx;

	public TrashPurgeService(UserRowLock userRowLock, Clock clock, PlatformTransactionManager txManager) {
		this.userRowLock = userRowLock;
		this.clock = clock;
		this.tx = Tx.write(txManager);
		this.readTx = Tx.readOnlyRequiresNew(txManager);
	}

	/** root 하나의 처리 결과. 삭제·보존은 목표 행 수, deletedActions는 지운 미완료 행동 수다. */
	record Outcome(boolean processed, int deletedGoals, int preservedGoals, int deletedActions) {
		static final Outcome SKIPPED = new Outcome(false, 0, 0, 0);
	}

	/** 한 회차의 합계. */
	public record Summary(int roots, int processed, int skipped, int failed, int deletedGoals, int preservedGoals,
			int deletedActions) {
	}

	record Root(GoalLevel level, long id, long userId) {
	}

	/** 기한이 지난 모든 root를 처리하고 INFO 로그 한 줄을 남긴다. 한 root의 실패가 나머지를 막지 않는다. */
	public Summary purgeExpired() {
		List<Root> roots = findExpiredRoots();
		int processed = 0;
		int skipped = 0;
		int failed = 0;
		int deletedGoals = 0;
		int preservedGoals = 0;
		int deletedActions = 0;
		for (Root root : roots) {
			try {
				Outcome outcome = purgeRoot(root);
				if (!outcome.processed()) {
					skipped++;
					continue;
				}
				processed++;
				deletedGoals += outcome.deletedGoals();
				preservedGoals += outcome.preservedGoals();
				deletedActions += outcome.deletedActions();
			} catch (RuntimeException e) {
				// 소유자 계정이 없거나 잠금 대기 초과 등(BusinessException·DataAccessException). 이 root는 다음 회차에 다시 시도한다.
				failed++;
				log.warn("휴지통 정리 실패: level={}, id={}, 원인={}", root.level(), root.id(), e.toString());
			}
		}
		log.info("휴지통 정리: root {}개(처리 {}, 건너뜀 {}, 실패 {}), 목표 삭제 {}개·보존 {}개, 미완료 행동 삭제 {}개",
				roots.size(), processed, skipped, failed, deletedGoals, preservedGoals, deletedActions);
		return new Summary(roots.size(), processed, skipped, failed, deletedGoals, preservedGoals, deletedActions);
	}

	/**
	 * 기한이 지난 직접 삭제 목표(root)를 상위 단계부터 읽는다. 잠금 없는 일반 읽기라 후보 목록일 뿐이며,
	 * 실제 처리 여부는 root마다 잠근 뒤 다시 확인한다.
	 */
	List<Root> findExpiredRoots() {
		LocalDateTime cutoff = GoalTrashService.truncate(UtcTime.now(clock)).minusDays(GoalTrashService.RETENTION_DAYS);
		return readTx.execute(status -> {
			List<Root> roots = new ArrayList<>();
			addRoots(roots, GoalLevel.YEARLY, "SELECT y.id, y.user_id FROM yearly_goals y "
					+ "WHERE y.deleted_at IS NOT NULL AND y.deleted_at < :cutoff ORDER BY y.id", cutoff);
			addRoots(roots, GoalLevel.MONTHLY, "SELECT m.id, y.user_id FROM monthly_goals m "
					+ "JOIN yearly_goals y ON y.id = m.yearly_goal_id "
					+ "WHERE m.deleted_at IS NOT NULL AND m.deleted_at < :cutoff ORDER BY m.id", cutoff);
			addRoots(roots, GoalLevel.WEEKLY, "SELECT w.id, y.user_id FROM weekly_goals w "
					+ "JOIN monthly_goals m ON m.id = w.monthly_goal_id JOIN yearly_goals y ON y.id = m.yearly_goal_id "
					+ "WHERE w.deleted_at IS NOT NULL AND w.deleted_at < :cutoff ORDER BY w.id", cutoff);
			return roots;
		});
	}

	private void addRoots(List<Root> roots, GoalLevel level, String sql, LocalDateTime cutoff) {
		for (Object row : em.createNativeQuery(sql).setParameter("cutoff", cutoff).getResultList()) {
			Object[] cols = (Object[]) row;
			roots.add(new Root(level, ((Number) cols[0]).longValue(), ((Number) cols[1]).longValue()));
		}
	}

	/** root 하나를 자기 트랜잭션에서 처리한다. */
	Outcome purgeRoot(Root root) {
		return tx.execute(status -> {
			// 1. 소유자 users 행 잠금(트랜잭션 첫 쿼리).
			userRowLock.lock(root.userId());

			// 2. root 행을 SKIP LOCKED로 다시 읽는다. 잠금을 얻은 뒤의 상태가 기준이다(그 사이 정리·복원됐을 수 있다).
			LocalDateTime cutoff = GoalTrashService.truncate(UtcTime.now(clock)).minusDays(GoalTrashService.RETENTION_DAYS);
			String table = switch (root.level()) {
				case YEARLY -> "yearly_goals";
				case MONTHLY -> "monthly_goals";
				case WEEKLY -> "weekly_goals";
			};
			List<?> locked = em.createNativeQuery("SELECT id FROM " + table
					+ " WHERE id = :id AND deleted_at IS NOT NULL AND deleted_at < :cutoff FOR UPDATE SKIP LOCKED")
					.setParameter("id", root.id()).setParameter("cutoff", cutoff).getResultList();
			if (locked.isEmpty()) {
				return Outcome.SKIPPED;
			}

			// root의 모든 자손(삭제 여부와 무관)을 모은다.
			List<Long> monthlyIds = new ArrayList<>();
			List<Long> weeklyIds = new ArrayList<>();
			switch (root.level()) {
				case YEARLY -> {
					monthlyIds.addAll(ids("SELECT id FROM monthly_goals WHERE yearly_goal_id = :id", "id", root.id()));
					weeklyIds.addAll(idsIn("SELECT id FROM weekly_goals WHERE monthly_goal_id IN (:ids)", monthlyIds));
				}
				case MONTHLY -> {
					monthlyIds.add(root.id());
					weeklyIds.addAll(idsIn("SELECT id FROM weekly_goals WHERE monthly_goal_id IN (:ids)", monthlyIds));
				}
				case WEEKLY -> weeklyIds.add(root.id());
			}

			int deletedActions = 0;
			int deletedGoals = 0;
			int preservedGoals = 0;

			// 3-1. 미완료 행동 hard-delete(삭제됐든 아니든). 완료 행동은 과거 이력이라 남긴다.
			if (!weeklyIds.isEmpty()) {
				deletedActions = em.createNativeQuery(
						"DELETE FROM actions WHERE weekly_goal_id IN (:ids) AND is_completed = FALSE")
						.setParameter("ids", weeklyIds).executeUpdate();
			}

			// 3-2. 주간: 남은(=완료한) 행동도 수치 기록도 없는 것만 지운다.
			if (!weeklyIds.isEmpty()) {
				Set<Long> keep = new HashSet<>();
				keep.addAll(idsIn("SELECT DISTINCT weekly_goal_id FROM actions WHERE weekly_goal_id IN (:ids)", weeklyIds));
				keep.addAll(idsIn("SELECT DISTINCT weekly_goal_id FROM weekly_goal_numeric_records "
						+ "WHERE weekly_goal_id IN (:ids)", weeklyIds));
				List<Long> remove = weeklyIds.stream().filter(w -> !keep.contains(w)).toList();
				if (!remove.isEmpty()) {
					em.createNativeQuery("DELETE FROM weekly_goals WHERE id IN (:ids)")
							.setParameter("ids", remove).executeUpdate();
				}
				deletedGoals += remove.size();
				preservedGoals += keep.size();
			}

			// 3-3. 월간: 남은 하위 주간 목표가 없는 것만 지운다.
			if (!monthlyIds.isEmpty()) {
				Set<Long> keep = new HashSet<>(
						idsIn("SELECT DISTINCT monthly_goal_id FROM weekly_goals WHERE monthly_goal_id IN (:ids)", monthlyIds));
				List<Long> remove = monthlyIds.stream().filter(m -> !keep.contains(m)).toList();
				if (!remove.isEmpty()) {
					em.createNativeQuery("DELETE FROM monthly_goals WHERE id IN (:ids)")
							.setParameter("ids", remove).executeUpdate();
				}
				deletedGoals += remove.size();
				preservedGoals += keep.size();
			}

			// 3-4. 연간(root가 연간일 때만): 남은 하위 월간도, 달성한 기준도 없으면 지운다(기준 행은 CASCADE).
			if (root.level() == GoalLevel.YEARLY) {
				long monthlyLeft = count("SELECT COUNT(*) FROM monthly_goals WHERE yearly_goal_id = :id", root.id());
				long achieved = count("SELECT COUNT(*) FROM yearly_goal_criteria "
						+ "WHERE yearly_goal_id = :id AND is_achieved = TRUE", root.id());
				if (monthlyLeft == 0 && achieved == 0) {
					em.createNativeQuery("DELETE FROM yearly_goals WHERE id = :id")
							.setParameter("id", root.id()).executeUpdate();
					deletedGoals++;
				} else {
					preservedGoals++;
				}
			}
			return new Outcome(true, deletedGoals, preservedGoals, deletedActions);
		});
	}

	private List<Long> ids(String sql, String name, Object value) {
		List<Long> result = new ArrayList<>();
		for (Object row : em.createNativeQuery(sql).setParameter(name, value).getResultList()) {
			result.add(((Number) row).longValue());
		}
		return result;
	}

	private List<Long> idsIn(String sql, List<Long> in) {
		if (in.isEmpty()) {
			return List.of();
		}
		return ids(sql, "ids", in);
	}

	private long count(String sql, long id) {
		return ((Number) em.createNativeQuery(sql).setParameter("id", id).getSingleResult()).longValue();
	}
}
