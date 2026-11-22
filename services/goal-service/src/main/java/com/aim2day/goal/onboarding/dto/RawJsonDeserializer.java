package com.aim2day.goal.onboarding.dto;

import java.io.StringWriter;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * 요청 본문의 JSON 값(객체·배열·스칼라)을 해석하지 않고 JSON 텍스트 그대로 읽는다.
 * 트리(JsonNode)로 읽으면 소수가 double로 바뀌어 0.12345678901234567890이 깎이므로, 파서 토큰을 숫자 원문 그대로
 * 복사(copyCurrentStructureExact)해 다시 쓴다. 공백은 없어지고(컴팩트) 한글 등은 이스케이프 없이 그대로 나간다.
 * JSON null은 Jackson이 이 클래스를 거치지 않고 null로 넘긴다(검증에서 REQUIRED로 처리).
 */
public class RawJsonDeserializer extends ValueDeserializer<String> {

	private static final JsonMapper WRITER = JsonMapper.builder().build();

	/** 현재 JSON 값을 숫자 정밀도를 잃지 않고 컴팩트한 JSON 문자열로 복사해 돌려준다. */
	@Override
	public String deserialize(JsonParser p, DeserializationContext ctxt) {
		StringWriter out = new StringWriter();
		try (JsonGenerator generator = WRITER.createGenerator(out)) {
			generator.copyCurrentStructureExact(p);
		}
		return out.toString();
	}
}
