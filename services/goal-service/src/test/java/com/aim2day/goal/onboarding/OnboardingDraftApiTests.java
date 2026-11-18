package com.aim2day.goal.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class OnboardingDraftApiTests extends OnboardingTestBase {

	private static final String SAMPLE = "{\"yearlyGoals\":[{\"tempId\":\"y1\",\"title\":\"백엔드 개발자 취업 준비\"}]}";

	private String put(Integer step, String draftData, Long version) {
		StringBuilder sb = new StringBuilder("{");
		if (step != null) {
			sb.append("\"currentStep\":").append(step).append(',');
		}
		if (draftData != null) {
			sb.append("\"draftData\":").append(draftData).append(',');
		}
		if (version != null) {
			sb.append("\"version\":").append(version).append(',');
		}
		if (sb.length() > 1) {
			sb.setLength(sb.length() - 1);
		}
		return sb.append('}').toString();
	}

	// ------------------------------------------------------------ 기본 흐름

	@Test
	void getReturnsNullDataWhenNoDraft() throws Exception {
		MvcResult result = api.get(DRAFT, 1L);
		api.expect(result, 200, "$.code", "SUCCESS");
		assertThat(api.body(result)).contains("\"data\":null");
	}

	@Test
	void firstPutCreatesWithFirstVersionAndGetReturnsIt() throws Exception {
		MvcResult created = api.put(DRAFT, 1L, put(2, SAMPLE, null));
		api.expect(created, 200,
				"$.data.currentStep", 2,
				"$.data.draftData.yearlyGoals[0].tempId", "y1",
				"$.data.draftData.yearlyGoals[0].title", "백엔드 개발자 취업 준비",
				"$.data.version", firstVersion());
		assertThat((String) JsonPath.read(api.body(created), "$.data.updatedAt")).endsWith("Z");

		api.expect(api.get(DRAFT, 1L), 200,
				"$.data.currentStep", 2,
				"$.data.draftData.yearlyGoals[0].title", "백엔드 개발자 취업 준비",
				"$.data.version", firstVersion());
		assertThat(count("SELECT COUNT(*) FROM onboarding_drafts WHERE user_id = 1")).isEqualTo(1);
	}

	@Test
	void putWithCurrentVersionOverwritesAndBumpsVersion() throws Exception {
		api.expect(api.put(DRAFT, 1L, put(1, "{\"a\":1}", null)), 200, "$.data.version", firstVersion());
		MvcResult updated = api.put(DRAFT, 1L, put(3, "{\"a\":2}", firstVersion()));
		api.expect(updated, 200, "$.data.currentStep", 3, "$.data.draftData.a", 2, "$.data.version", firstVersion() + 1);
		assertThat((String) JsonPath.read(api.body(updated), "$.data.updatedAt")).endsWith("Z");
		api.expect(api.get(DRAFT, 1L), 200, "$.data.currentStep", 3, "$.data.draftData.a", 2, "$.data.version", firstVersion() + 1);
		assertThat(count("SELECT COUNT(*) FROM onboarding_drafts")).isEqualTo(1);
	}

	@Test
	void deleteRemovesDraftAndIsIdempotent() throws Exception {
		putDraft(1L, 1, "{\"a\":1}");
		MvcResult first = api.delete(DRAFT, 1L);
		api.expect(first, 200, "$.code", "SUCCESS");
		assertThat(api.body(first)).contains("\"data\":null");
		assertThat(count("SELECT COUNT(*) FROM onboarding_drafts")).isZero();
		// 없어도 200이다.
		api.expect(api.delete(DRAFT, 1L), 200, "$.code", "SUCCESS");
		assertThat(api.body(api.get(DRAFT, 1L))).contains("\"data\":null");
	}

	@Test
	void draftsAreOwnedByTheLoggedInUser() throws Exception {
		putDraft(1L, 2, "{\"owner\":\"one\"}");
		// 사용자 2는 자기 임시 저장이 없고, 사용자 1의 것은 보이지도 지워지지도 않는다.
		assertThat(api.body(api.get(DRAFT, 2L))).contains("\"data\":null");
		api.expect(api.delete(DRAFT, 2L), 200);
		api.expect(api.get(DRAFT, 1L), 200, "$.data.draftData.owner", "one");
		// 사용자 2의 첫 저장은 사용자 1의 version과 무관하게 새로 만든다.
		api.expect(api.put(DRAFT, 2L, put(1, "{\"owner\":\"two\"}", null)), 200, "$.data.version", firstVersion());
		api.expect(api.get(DRAFT, 1L), 200, "$.data.draftData.owner", "one");
		assertThat(count("SELECT COUNT(*) FROM onboarding_drafts")).isEqualTo(2);
	}

	// ------------------------------------------------------------ version 규칙 네 가지

	@Test
	void putWithoutVersionWhenDraftExistsIs409WithCurrentVersion() throws Exception {
		putDraft(1L, 1, "{\"a\":1}");
		api.expect(api.put(DRAFT, 1L, put(2, "{\"a\":2}", null)), 409,
				"$.code", "VERSION_CONFLICT", "$.details.currentVersion", firstVersion());
		// 덮어쓰이지 않았다.
		api.expect(api.get(DRAFT, 1L), 200, "$.data.draftData.a", 1, "$.data.currentStep", 1);
	}

	@Test
	void putWithStaleVersionIs409WithCurrentVersion() throws Exception {
		putDraft(1L, 1, "{\"a\":1}");
		api.expect(api.put(DRAFT, 1L, put(2, "{\"a\":2}", firstVersion())), 200, "$.data.version", firstVersion() + 1);
		api.expect(api.put(DRAFT, 1L, put(3, "{\"a\":3}", firstVersion())), 409,
				"$.code", "VERSION_CONFLICT", "$.details.currentVersion", firstVersion() + 1);
		api.expect(api.get(DRAFT, 1L), 200, "$.data.draftData.a", 2);
	}

	@Test
	void putWithVersionWhenNoDraftIs409WithNullCurrentVersion() throws Exception {
		// 다른 탭이 온보딩을 끝내 임시 저장을 지운 뒤, 오래된 탭이 같은 version으로 되살리지 못한다.
		putDraft(1L, 1, "{\"a\":1}");
		api.expect(api.delete(DRAFT, 1L), 200);
		MvcResult result = api.put(DRAFT, 1L, put(2, "{\"a\":2}", firstVersion()));
		api.expect(result, 409, "$.code", "VERSION_CONFLICT");
		assertThat(api.body(result)).contains("\"currentVersion\":null");
		assertThat(count("SELECT COUNT(*) FROM onboarding_drafts")).isZero();
	}

	@Test
	void recreatedDraftAfterDeleteGetsNewVersionSoStaleTabCannotOverwrite() throws Exception {
		MvcResult first = api.put(DRAFT, 1L, put(1, "{\"a\":1}", null));
		long oldVersion = ((Number) JsonPath.read(api.body(first), "$.data.version")).longValue();
		api.expect(api.delete(DRAFT, 1L), 200);
		MvcResult again = api.put(DRAFT, 1L, put(2, "{\"a\":2}", null));
		long newVersion = ((Number) JsonPath.read(api.body(again), "$.data.version")).longValue();
		assertThat(newVersion).isGreaterThan(oldVersion);
		// 예전 탭의 version으로는 덮어쓸 수 없다.
		api.expect(api.put(DRAFT, 1L, put(3, "{\"a\":3}", oldVersion)), 409,
				"$.code", "VERSION_CONFLICT", "$.details.currentVersion", newVersion);
		api.expect(api.get(DRAFT, 1L), 200, "$.data.draftData.a", 2);
		// 새 version으로는 +1로 갱신된다.
		api.expect(api.put(DRAFT, 1L, put(3, "{\"a\":3}", newVersion)), 200, "$.data.version", newVersion + 1);
	}

	@Test
	void versionKeepsIncreasingAcrossRepeatedDeleteAndRecreateAndIsPerUser() throws Exception {
		long previous = 0;
		for (int i = 0; i < 4; i++) {
			MvcResult created = api.put(DRAFT, 1L, put(1, "{\"i\":" + i + "}", null));
			long created1 = ((Number) JsonPath.read(api.body(created), "$.data.version")).longValue();
			assertThat(created1).isGreaterThan(previous);
			// 수정으로 +1 된 값보다도 다음 재생성이 커야 한다.
			MvcResult updated = api.put(DRAFT, 1L, put(2, "{\"i\":" + i + "}", created1));
			previous = ((Number) JsonPath.read(api.body(updated), "$.data.version")).longValue();
			assertThat(previous).isEqualTo(created1 + 1);
			api.expect(api.delete(DRAFT, 1L), 200);
		}
		// 다른 사용자의 카운터는 독립이다: 사용자 2의 첫 저장은 1에서 시작한다.
		api.expect(api.put(DRAFT, 2L, put(1, "{\"a\":1}", null)), 200, "$.data.version", firstVersion());
	}

	@Test
	void putWithoutVersionWhenNoDraftCreates() throws Exception {
		api.expect(api.put(DRAFT, 1L, put(4, "{\"a\":1}", null)), 200, "$.data.version", firstVersion(), "$.data.currentStep", 4);
	}

	// ------------------------------------------------------------ 입력 검증

	@Test
	void rejectsMissingOrOutOfRangeStep() throws Exception {
		assertFieldError(api.put(DRAFT, 1L, put(null, "{}", null)), "currentStep", "REQUIRED");
		assertFieldError(api.put(DRAFT, 1L, put(0, "{}", null)), "currentStep", "OUT_OF_RANGE");
		assertFieldError(api.put(DRAFT, 1L, put(5, "{}", null)), "currentStep", "OUT_OF_RANGE");
		assertThat(count("SELECT COUNT(*) FROM onboarding_drafts")).isZero();
		// 1과 4는 경계 안이다.
		api.expect(api.put(DRAFT, 1L, put(1, "{}", null)), 200);
	}

	@Test
	void rejectsMissingOrNullDraftData() throws Exception {
		assertFieldError(api.put(DRAFT, 1L, put(1, null, null)), "draftData", "REQUIRED");
		assertFieldError(api.put(DRAFT, 1L, put(1, "null", null)), "draftData", "REQUIRED");
		assertThat(count("SELECT COUNT(*) FROM onboarding_drafts")).isZero();
	}

	@Test
	void collectsBothFieldErrors() throws Exception {
		MvcResult result = api.put(DRAFT, 1L, "{}");
		assertFieldError(result, "currentStep", "REQUIRED");
		assertFieldError(result, "draftData", "REQUIRED");
	}

	@Test
	void malformedJsonIs400Malformed() throws Exception {
		api.expect(api.put(DRAFT, 1L, "{\"currentStep\":1,\"draftData\":{"), 400, "$.code", "MALFORMED_REQUEST");
	}

	// ------------------------------------------------------------ JSON 왕복 (Review Focus 4)

	@Test
	void roundTripKeepsNestedStructureUnicodeAndPrecision() throws Exception {
		String data = "{\"yearlyGoals\":[{\"tempId\":\"y1\",\"title\":\"한글 😀 \\\"따옴표\\\" \\\\ 역슬래시\",\"tags\":[1,[2,{\"x\":null}],true]}],"
				+ "\"precise\":0.123456789012345,\"big\":9007199254740991,\"int\":42,\"neg\":-0.5,"
				+ "\"empty\":{},\"emptyList\":[],\"flag\":false}";
		MvcResult put = api.put(DRAFT, 1L, put(2, data, null));
		api.expect(put, 200);
		MvcResult got = api.get(DRAFT, 1L);
		api.expect(got, 200);
		for (MvcResult r : new MvcResult[] {put, got}) {
			String body = api.body(r);
			// 객체는 객체로 돌아온다(문자열로 감싸지 않는다).
			Object draftData = JsonPath.read(body, "$.data.draftData");
			assertThat(draftData).isInstanceOf(Map.class);
			// 숫자는 double 범위로 보존된다(계약: 정수는 +-2^53까지, 소수는 유효숫자 15자리). MySQL JSON이 double로 저장해
			// 그보다 긴 정밀도는 보장하지 않으므로 계약 안의 값으로만 확인한다.
			assertThat(body).contains("\"precise\":0.123456789012345");
			assertThat(body).contains("\"big\":9007199254740991");
			assertThat(body).contains("\"neg\":-0.5");
			assertThat(body).contains("\"title\":\"한글 😀 \\\"따옴표\\\" \\\\ 역슬래시\"");
			assertThat(body).contains("\"tags\":[1,[2,{\"x\":null}],true]");
			assertThat(body).contains("\"empty\":{}").contains("\"emptyList\":[]").contains("\"flag\":false");
		}
		// DB에는 JSON 객체로 들어 있다(문자열 스칼라가 아니다). MySQL의 JSON_TYPE = 'OBJECT'에 해당하는 H2 검사다.
		assertThat(count("SELECT COUNT(*) FROM onboarding_drafts WHERE draft_data IS JSON OBJECT")).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM onboarding_drafts WHERE draft_data IS JSON SCALAR")).isZero();
	}

	@Test
	void updateKeepsObjectTypeInTheDatabase() throws Exception {
		putDraft(1L, 1, "{\"a\":1}");
		api.expect(api.put(DRAFT, 1L, put(2, "{\"a\":{\"b\":[1,2]}}", firstVersion())), 200, "$.data.draftData.a.b[1]", 2);
		assertThat(count("SELECT COUNT(*) FROM onboarding_drafts WHERE draft_data IS JSON OBJECT")).isEqualTo(1);
	}

	@Test
	void anyNonNullJsonValueIsAccepted() throws Exception {
		// 서버는 내용을 해석하지 않는다. 배열·문자열·숫자도 그대로 돌려준다.
		api.expect(api.put(DRAFT, 1L, put(1, "[1,2,3]", null)), 200, "$.data.draftData[2]", 3);
		api.expect(api.put(DRAFT, 1L, put(1, "\"문자열\"", firstVersion())), 200, "$.data.draftData", "문자열");
		api.expect(api.get(DRAFT, 1L), 200, "$.data.draftData", "문자열");
		assertThat(count("SELECT COUNT(*) FROM onboarding_drafts WHERE draft_data IS JSON SCALAR")).isEqualTo(1);
		api.expect(api.put(DRAFT, 1L, put(1, "false", firstVersion() + 1)), 200, "$.data.draftData", false);
	}

	// ------------------------------------------------------------ 64KB 경계 (Review Focus 5)

	/** 직렬화하면 정확히 bytes 바이트가 되는 객체 {"k":"가가...aa"}. 한글은 3바이트라 경계를 문자 수가 아닌 바이트로 확인한다. */
	private static String objectOfBytes(int bytes) {
		int overhead = "{\"k\":\"\"}".getBytes(StandardCharsets.UTF_8).length; // 8
		int payload = bytes - overhead;
		StringBuilder sb = new StringBuilder("{\"k\":\"");
		sb.append("가".repeat(payload / 3)).append("a".repeat(payload % 3));
		sb.append("\"}");
		assertThat(sb.toString().getBytes(StandardCharsets.UTF_8)).hasSize(bytes);
		return sb.toString();
	}

	@Test
	void exactly65536BytesIsAccepted() throws Exception {
		MvcResult result = api.put(DRAFT, 1L, put(1, objectOfBytes(65_536), null));
		api.expect(result, 200);
		// 돌려받은 값도 같은 바이트 수다.
		Map<String, Object> data = JsonPath.read(api.body(api.get(DRAFT, 1L)), "$.data.draftData");
		assertThat(((String) data.get("k")).getBytes(StandardCharsets.UTF_8)).hasSize(65_536 - 8);
	}

	@Test
	void bytes65537IsRejectedAsTooLong() throws Exception {
		assertFieldError(api.put(DRAFT, 1L, put(1, objectOfBytes(65_537), null)), "draftData", "TOO_LONG");
		assertThat(count("SELECT COUNT(*) FROM onboarding_drafts")).isZero();
	}

	@Test
	void sizeIsMeasuredInBytesNotCharacters() throws Exception {
		// 문자 수는 3만 자도 안 되지만 바이트로는 한도를 넘는다.
		String tooBigInBytes = objectOfBytes(65_538);
		assertThat(tooBigInBytes.length()).isLessThan(30_000);
		assertFieldError(api.put(DRAFT, 1L, put(1, tooBigInBytes, null)), "draftData", "TOO_LONG");
	}

	@Test
	void requiresLogin() throws Exception {
		MvcResult unauthenticated = mockMvc.perform(MockMvcRequestBuilders.get(DRAFT)).andReturn();
		assertThat(unauthenticated.getResponse().getStatus()).isEqualTo(401);
	}
}
