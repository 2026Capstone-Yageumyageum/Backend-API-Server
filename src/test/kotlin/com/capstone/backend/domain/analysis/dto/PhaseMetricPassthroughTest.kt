package com.capstone.backend.domain.analysis.dto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * 분석 서버가 보낸 phaseMetrics가 앱까지 살아남는지 확인한다.
 *
 * PlayerAnalysisDto는 고정 필드만 갖는다. 필드를 선언하지 않으면 Jackson이 조용히
 * 버리므로, 분석 서버를 고쳐도 앱에는 아무것도 도달하지 않는다.
 */
class PhaseMetricPassthroughTest {
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()

    private val payload =
        """
        {
          "analysisId": "a1",
          "proId": "7",
          "overallScore": 79.3,
          "phaseScores": [],
          "phaseMetrics": [
            {
              "phase": "leg_lift",
              "key": "leg_lift_knee_height",
              "label": "디딤 무릎 높이",
              "unit": "degree",
              "userValue": 0.42,
              "proValue": 0.35,
              "difference": 0.07,
              "threshold": 0.12,
              "status": "good",
              "favorableDirection": "positive",
              "why": "에너지 축적과 직결됩니다.",
              "userJoints": ["right_shoulder", "right_elbow"],
              "proJoints": ["left_shoulder", "left_elbow"],
              "userFrame": 41,
              "proFrame": 38
            }
          ]
        }
        """.trimIndent()

    @Test
    @DisplayName("phaseMetrics가 역직렬화된다")
    fun deserializesPhaseMetrics() {
        val dto = mapper.readValue(payload, PlayerAnalysisDto::class.java)

        assertThat(dto.phaseMetrics).hasSize(1)
        val metric = dto.phaseMetrics!!.first()
        assertThat(metric.key).isEqualTo("leg_lift_knee_height")
        assertThat(metric.status).isEqualTo("good")
        assertThat(metric.difference).isEqualTo(0.07)
        assertThat(metric.userFrame).isEqualTo(41.0)
        assertThat(metric.why).isNotBlank()
    }

    @Test
    @DisplayName("재직렬화해도 phaseMetrics가 살아남는다 — detailJson에 실려야 한다")
    fun survivesReserialization() {
        val dto = mapper.readValue(payload, PlayerAnalysisDto::class.java)

        val json = mapper.writeValueAsString(dto)

        assertThat(json).contains("phaseMetrics")
        assertThat(json).contains("leg_lift_knee_height")
    }

    @Test
    @DisplayName("구버전 분석 서버 응답(phaseMetrics 없음)도 깨지지 않는다")
    fun toleratesMissingPhaseMetrics() {
        val legacy = """{"analysisId":"a1","proId":"7","overallScore":79.3,"phaseScores":[]}"""

        val dto = mapper.readValue(legacy, PlayerAnalysisDto::class.java)

        assertThat(dto.phaseMetrics).isNull()
    }

    @Test
    @DisplayName("각도 지표의 unit이 역직렬화된다")
    fun deserializesUnit() {
        val dto = mapper.readValue(payload, PlayerAnalysisDto::class.java)

        assertThat(dto.phaseMetrics!!.first().unit).isEqualTo("degree")
    }

    @Test
    @DisplayName("unit 없는 축 지표는 null이 된다")
    fun toleratesMissingUnit() {
        val axisOnly =
            """
            {
              "analysisId": "a1", "proId": "7", "overallScore": 79.3, "phaseScores": [],
              "phaseMetrics": [
                { "phase": "stride", "key": "stride_foot_width", "label": "디딤발 착지 폭", "status": "good" }
              ]
            }
            """.trimIndent()

        val dto = mapper.readValue(axisOnly, PlayerAnalysisDto::class.java)

        assertThat(dto.phaseMetrics!!.first().unit).isNull()
    }

    @Test
    @DisplayName("측정 관절 이름이 역직렬화된다")
    fun deserializesJoints() {
        val dto = mapper.readValue(payload, PlayerAnalysisDto::class.java)

        val metric = dto.phaseMetrics!!.first()
        assertThat(metric.userJoints).containsExactly("right_shoulder", "right_elbow")
        assertThat(metric.proJoints).containsExactly("left_shoulder", "left_elbow")
    }

    @Test
    @DisplayName("관절 이름이 없는 구버전 응답도 깨지지 않는다")
    fun toleratesMissingJoints() {
        val legacy =
            """
            {
              "analysisId": "a1", "proId": "7", "overallScore": 79.3, "phaseScores": [],
              "phaseMetrics": [
                { "phase": "stride", "key": "stride_foot_width", "label": "디딤발 착지 폭", "status": "good" }
              ]
            }
            """.trimIndent()

        val dto = mapper.readValue(legacy, PlayerAnalysisDto::class.java)

        assertThat(dto.phaseMetrics!!.first().userJoints).isNull()
        assertThat(dto.phaseMetrics!!.first().proJoints).isNull()
    }
}
