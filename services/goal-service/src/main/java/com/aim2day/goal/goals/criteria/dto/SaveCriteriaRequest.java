package com.aim2day.goal.goals.criteria.dto;

import java.util.List;

/** 검증은 서비스가 한다. 항목별 오류 위치(items[i].text)를 알려야 하고, 기존 기준 id와도 대조해야 하기 때문이다. */
public record SaveCriteriaRequest(Long goalVersion, List<Item> items, String memo) {

	public record Item(Long id, String text, Integer weight) {
	}
}
