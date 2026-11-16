package com.aim2day.goal.goals.yearly.dto;

import com.aim2day.goal.goals.GoalStatus;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.util.Optional;

/**
 * PATCH 본문. 각 필드는 세 상태를 구분한다.
 *  - 필드 없음: Java null            -> 바꾸지 않는다.
 *  - JSON null: Optional.empty()      -> 값을 지운다(연간 목표에서는 description만 허용, 나머지는 400).
 *  - 값 있음: Optional.of(값)         -> 그 값으로 바꾼다.
 * targetYear는 바꿀 수 없으므로 받지 않는다(보내도 무시된다).
 *
 * record가 아니라 setter를 쓰는 클래스인 이유: Jackson 3의 record는 생성자 인자가 비어 있을 때
 * Optional 필드를 Optional.empty()로 채워 "필드 없음"과 "JSON null"이 구분되지 않는다.
 * setter 방식은 JSON에 필드가 있을 때만 호출되므로 없는 필드는 초기값 null로 남는다.
 */
public class UpdateYearlyGoalRequest {

	private Optional<String> title;
	private Optional<String> description;
	private Optional<String> color;
	private Optional<GoalStatus> status;
	private Long version;

	public Optional<String> title() {
		return title;
	}

	public Optional<String> description() {
		return description;
	}

	public Optional<String> color() {
		return color;
	}

	public Optional<GoalStatus> status() {
		return status;
	}

	public Long version() {
		return version;
	}

	@JsonSetter("title")
	public void setTitle(Optional<String> title) {
		this.title = title;
	}

	@JsonSetter("description")
	public void setDescription(Optional<String> description) {
		this.description = description;
	}

	@JsonSetter("color")
	public void setColor(Optional<String> color) {
		this.color = color;
	}

	@JsonSetter("status")
	public void setStatus(Optional<GoalStatus> status) {
		this.status = status;
	}

	@JsonSetter("version")
	public void setVersion(Long version) {
		this.version = version;
	}
}
