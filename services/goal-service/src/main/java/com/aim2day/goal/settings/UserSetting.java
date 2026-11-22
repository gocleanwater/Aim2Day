package com.aim2day.goal.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.DayOfWeek;

/**
 * 사용자 환경설정. created_at/updated_at은 DB 기본값에 맡기고 엔티티에 매핑하지 않는다(응답에도 쓰지 않는다).
 */
@Entity
@Table(name = "user_settings")
public class UserSetting {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "week_start_day", nullable = false)
	private DayOfWeek weekStartDay;

	@Column(nullable = false)
	private String timezone;

	/** 낙관적 락. 오래된 화면에서 온 수정이 최신 값을 덮어쓰지 못하게 한다. */
	@Version
	private Long version;

	protected UserSetting() {
	}

	public UserSetting(Long userId, DayOfWeek weekStartDay, String timezone) {
		this.userId = userId;
		this.weekStartDay = weekStartDay;
		this.timezone = timezone;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public DayOfWeek getWeekStartDay() {
		return weekStartDay;
	}

	public String getTimezone() {
		return timezone;
	}

	public Long getVersion() {
		return version;
	}

	/** 주 시작 요일과 시간대를 바꾼다. 검증(IANA 시간대 이름)과 version 비교는 서비스가 한다. */
	public void change(DayOfWeek weekStartDay, String timezone) {
		this.weekStartDay = weekStartDay;
		this.timezone = timezone;
	}
}
