package com.aim2day.goal.progress;

import com.aim2day.goal.goals.monthly.MonthlyGoalRepository;
import com.aim2day.goal.goals.weekly.MeasurementType;
import com.aim2day.goal.goals.weekly.WeeklyGoalRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주간·월간 진행률 변경의 유일한 경로(ERD 결정 16·17). 호출자 트랜잭션 안에서만 쓴다(MANDATORY).
 *
 * 동시성 규칙
 * 1. current_value는 UPDATE ... SET current_value = current_value + :delta 로 DB가 더한다. 같은 주간 목표에
 *    두 인스턴스가 동시에 기록해도 기여량이 사라지지 않는다.
 * 2. 진행률을 계산하기 전에 해당 행을 SELECT ... FOR UPDATE로 잠그고 값을 다시 읽는다. 잠금 전에 읽은 값은 쓰지 않는다.
 *    락킹 읽기는 MySQL REPEATABLE READ에서도 스냅샷이 아니라 최신 커밋 값을 읽는(current read) 문장이다.
 *    그래서 JPA 엔티티가 아니라 네이티브 쿼리로 읽는다. 엔티티는 영속성 컨텍스트에 이미 있으면 낡은 값을 돌려준다.
 * 3. 잠금 순서는 users -> 주간 -> 월간이다. users 행은 이 클래스가 잡지 않는다. 진행 기록 INSERT가 FK 확인으로
 *    users 행에 공유 잠금을 걸기 때문에, 호출자(서비스)가 트랜잭션 첫 쿼리로 UserRowLock.lock을 불러야 한다.
 *    그러면 같은 사용자의 진행률 변경 경로가 모두 users 행에서 한 줄로 서고, 이 클래스의 형제 주간 목표 읽기(아래)도
 *    앞선 요청의 커밋을 본다. 월간 목표 두 개를 잠글 때는 id 오름차순이다(호출자 책임).
 * 4. 형제 주간 목표의 progress_rate는 일반 SELECT로 읽는다. FOR UPDATE로 읽으면 "내 주간 -> 월간 -> 형제 주간"과
 *    "형제의 주간 -> 월간"이 서로 기다리는 교착이 생긴다. 일반 읽기가 최신 값을 보려면 이 트랜잭션의 스냅샷이
 *    users 행 잠금 이후에 만들어져야 하므로 3번 규칙이 필수다.
 * 5. 엔티티 저장과 진행률 UPDATE를 섞어도 안전하도록, 진행률 UPDATE는 영속성 컨텍스트를 비우지 않는다.
 *    호출자가 응답을 만들 때는 낡은 엔티티를 쓰지 말고 새로 읽는다.
 */
@Component
public class ProgressEngine {

	private static final int MEMO_MAX = 255;

	@PersistenceContext
	private EntityManager em;

	private final WeeklyGoalRepository weeklies;
	private final MonthlyGoalRepository monthlies;
	private final WeeklyActionCounter actionCounter;
	private final ProgressLogWriter logs;

	public ProgressEngine(WeeklyGoalRepository weeklies, MonthlyGoalRepository monthlies,
			WeeklyActionCounter actionCounter, ProgressLogWriter logs) {
		this.weeklies = weeklies;
		this.monthlies = monthlies;
		this.actionCounter = actionCounter;
		this.logs = logs;
	}

	/** 수치형 현재값을 원자적으로 더한다(음수 delta는 차감). 진행률은 이어서 recalcWeekly로 다시 계산한다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void addNumeric(long weeklyGoalId, BigDecimal delta) {
		if (weeklies.addCurrentValue(weeklyGoalId, delta) != 1) {
			throw new IllegalStateException("주간 목표가 없다: " + weeklyGoalId);
		}
	}

	/**
	 * 월간 목표 행만 잠근다. 새 주간 목표 INSERT 전에 부른다. INSERT의 FK 확인이 월간 행에 공유 잠금을 걸고,
	 * 이어 recalcMonthly가 배타 잠금으로 올리려 하면 같은 월간 아래의 두 INSERT가 서로를 기다린다.
	 * 먼저 배타 잠금을 잡아 두면 이 교착이 없다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void lockMonthly(long monthlyGoalId) {
		em.createNativeQuery("SELECT id FROM monthly_goals WHERE id = :id FOR UPDATE")
				.setParameter("id", monthlyGoalId).getResultList();
	}

	/**
	 * 주간 진행률을 다시 계산하고 저장값과 다르면 UPDATE와 진행 기록을 남긴 뒤, 부모 월간 목표도 이어서 다시 계산한다.
	 * 진행률이 그대로여도 월간은 다시 계산한다(가중치·부모가 바뀐 경우가 있다).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public WeeklyProgress recalcWeekly(long userId, long weeklyGoalId, LocalDate logDate, String memo) {
		// 호출자가 엔티티를 바꾸고 아직 flush하지 않았을 수 있다. 네이티브 쿼리 전에 DB에 반영한다.
		em.flush();
		List<?> rows = em.createNativeQuery("SELECT measurement_type, target_value, current_value, progress_rate, "
				+ "monthly_goal_id FROM weekly_goals WHERE id = :id FOR UPDATE")
				.setParameter("id", weeklyGoalId).getResultList();
		if (rows.isEmpty()) {
			throw new IllegalStateException("주간 목표가 없다: " + weeklyGoalId);
		}
		Object[] row = (Object[]) rows.get(0);
		MeasurementType type = MeasurementType.valueOf((String) row[0]);
		BigDecimal target = (BigDecimal) row[1];
		BigDecimal current = (BigDecimal) row[2];
		BigDecimal stored = (BigDecimal) row[3];
		long monthlyGoalId = ((Number) row[4]).longValue();

		BigDecimal rate;
		Boolean targetReached = null;
		if (type == MeasurementType.NUMERIC) {
			rate = ProgressMath.weeklyNumeric(current, target);
			// 완료 판정은 반올림한 진행률이 아니라 원래 값 비교다. 9999.99 / 10000은 100.00으로 반올림되지만 미완료다.
			targetReached = target != null && current.compareTo(target) >= 0;
		} else {
			ActionCounts counts = actionCounter.counts(weeklyGoalId);
			rate = ProgressMath.weeklyChecklist(counts.completed(), counts.total());
		}

		if (rate.compareTo(stored) != 0) {
			weeklies.updateProgressRate(weeklyGoalId, rate);
			logs.record(userId, ProgressTargetType.WEEKLY_GOAL, weeklyGoalId, stored, rate,
					ProgressChangeType.PROGRESS_UPDATE, logDate, limit(memo));
		}
		recalcMonthly(userId, monthlyGoalId, logDate, memo);
		return new WeeklyProgress(weeklyGoalId, rate, current, targetReached);
	}

	/** 월간 진행률 = 삭제되지 않은 하위 주간 목표의 저장된 진행률의 가중 평균. 값이 바뀔 때만 UPDATE와 기록을 남긴다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public MonthlyProgress recalcMonthly(long userId, long monthlyGoalId, LocalDate logDate, String memo) {
		em.flush();
		List<?> rows = em.createNativeQuery("SELECT progress_rate FROM monthly_goals WHERE id = :id FOR UPDATE")
				.setParameter("id", monthlyGoalId).getResultList();
		if (rows.isEmpty()) {
			throw new IllegalStateException("월간 목표가 없다: " + monthlyGoalId);
		}
		BigDecimal stored = (BigDecimal) rows.get(0);

		List<WeightedRate> children = new ArrayList<>();
		for (Object r : em.createNativeQuery("SELECT progress_rate, weight FROM weekly_goals "
				+ "WHERE monthly_goal_id = :id AND deleted_at IS NULL")
				.setParameter("id", monthlyGoalId).getResultList()) {
			Object[] child = (Object[]) r;
			children.add(new WeightedRate((BigDecimal) child[0], (BigDecimal) child[1]));
		}
		BigDecimal rate = ProgressMath.monthly(children);

		if (rate.compareTo(stored) != 0) {
			monthlies.updateProgressRate(monthlyGoalId, rate);
			logs.record(userId, ProgressTargetType.MONTHLY_GOAL, monthlyGoalId, stored, rate,
					ProgressChangeType.PROGRESS_UPDATE, logDate, limit(memo));
		}
		return new MonthlyProgress(monthlyGoalId, rate, children.size());
	}

	private static String limit(String memo) {
		if (memo == null || memo.codePointCount(0, memo.length()) <= MEMO_MAX) {
			return memo;
		}
		return memo.substring(0, memo.offsetByCodePoints(0, MEMO_MAX));
	}
}
