package com.aim2day.goal.quadrant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * user_settings.last_quadrant_confirmed_date 읽기·쓰기. 설정 엔티티에 매핑하지 않고 SQL로 직접 다룬다.
 * 설정 수정이 아니므로 version을 올리지 않아야 하고(열려 있는 설정 화면이 409가 되지 않게),
 * 엔티티 저장이 이 열을 건드릴 일도 없어야 하기 때문이다. 호출자는 트랜잭션 안에서 부른다.
 */
@Component
public class ConfirmedDateStore {

	@PersistenceContext
	private EntityManager em;

	/** 사용자가 마지막으로 사분면 분류를 확정한 날짜를 읽는다. 한 번도 확정한 적이 없으면 null이다. */
	public LocalDate read(long userId) {
		var rows = em.createNativeQuery("SELECT last_quadrant_confirmed_date FROM user_settings WHERE user_id = :u")
				.setParameter("u", userId).getResultList();
		if (rows.isEmpty() || rows.get(0) == null) {
			return null;
		}
		Object value = rows.get(0);
		if (value instanceof LocalDate d) {
			return d;
		}
		if (value instanceof java.sql.Date d) {
			return d.toLocalDate();
		}
		return LocalDate.parse(value.toString());
	}

	/** 직접 UPDATE다(version 미증가). 설정 행은 호출 전에 SettingsService.userTime이 확보한다. */
	public void write(long userId, LocalDate date) {
		em.createNativeQuery("UPDATE user_settings SET last_quadrant_confirmed_date = :d WHERE user_id = :u")
				.setParameter("d", date).setParameter("u", userId).executeUpdate();
	}
}
