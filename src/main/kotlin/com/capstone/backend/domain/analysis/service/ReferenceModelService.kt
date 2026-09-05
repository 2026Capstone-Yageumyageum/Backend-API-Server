package com.capstone.backend.domain.analysis.service

import com.capstone.backend.domain.analysis.dto.BulkUploadResponse
import com.capstone.backend.domain.analysis.dto.ReferenceDataResponse
import com.capstone.backend.domain.analysis.entity.ReferenceModel
import com.capstone.backend.domain.analysis.repository.ReferenceModelRepository
import com.capstone.backend.domain.video.entity.SkeletonData
import com.capstone.backend.domain.video.repository.SkeletonDataRepository
import com.capstone.backend.global.exception.BusinessException
import com.capstone.backend.global.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.InputStream

/** 일괄 등록 JSON의 최상위가 객체일 때, 배열이 들어 있을 법한 키 후보들. */
private val ITEM_ARRAY_KEYS = listOf("items", "proSkeletonData", "pro_skeleton_data", "players", "data")

/** 골격 CSV가 담길 수 있는 키 후보들. 업로드 파일마다 표기가 달라 모두 허용한다. */
private val SKELETON_KEYS = listOf("skeletonData", "skeleton_data", "keypointsCsvText")

private const val FALLBACK_FPS = 60.0
private const val FALLBACK_RESOLUTION = "1920x1080"
private const val UNKNOWN_PITCHER = "알 수 없음"

/**
 * 프로 레퍼런스 모델의 등록·조회·삭제를 담당한다.
 *
 * 이전에는 이 로직이 전부 InternalAnalysisController 안에 있었다.
 * 컨트롤러가 레포지토리를 직접 주입받아 JSON 파싱부터 저장까지 처리했는데,
 * 가장 큰 문제는 트랜잭션 경계가 없다는 것이었다.
 * 일괄 등록 도중 실패하면 그때까지 저장된 행이 그대로 남아,
 * 재시도하면 중복 데이터가 쌓였다.
 */
@Service
class ReferenceModelService(
    private val objectMapper: ObjectMapper,
    private val skeletonDataRepository: SkeletonDataRepository,
    private val referenceModelRepository: ReferenceModelRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun findAll(): List<ReferenceDataResponse> =
        referenceModelRepository.findAll().map { model ->
            ReferenceDataResponse(
                proId = model.id!!,
                pitcherName = model.pitcherName,
                pitchType = model.pitchType,
                skeletonData = model.skeletonData.skeletonData,
            )
        }

    /**
     * 골격 파일 하나로 레퍼런스 모델을 등록한다.
     *
     * 파일은 CSV 원문일 수도, JSON으로 감싸인 형태일 수도 있어 양쪽을 모두 받는다.
     */
    @Transactional
    fun create(
        pitcherName: String,
        pitchType: String,
        sourceUrl: String?,
        frameCount: Int,
        fps: Double,
        resolution: String?,
        skeletonFile: InputStream,
        fileName: String?,
    ): Long {
        val raw = skeletonFile.readBytes().toString(Charsets.UTF_8)
        val skeleton =
            skeletonDataRepository.save(
                SkeletonData(
                    skeletonData = extractSkeletonCsv(raw, fileName),
                    frameCount = frameCount,
                    fps = fps,
                    resolution = resolution,
                ),
            )
        return referenceModelRepository
            .save(
                ReferenceModel(
                    pitcherName = pitcherName,
                    pitchType = pitchType,
                    sourceUrl = sourceUrl,
                    skeletonData = skeleton,
                ),
            ).id!!
    }

    /**
     * JSON 파일 하나로 여러 선수를 한 번에 등록한다.
     *
     * @Transactional이 붙어 있으므로 도중에 실패하면 전부 롤백된다.
     * 이전에는 트랜잭션이 없어 100건 중 50건째에서 실패하면 앞의 49건이 남았고,
     * 재시도할 때마다 중복이 쌓였다.
     *
     * 골격 데이터가 없는 항목은 건너뛰되 몇 건을 건너뛰었는지 함께 돌려준다.
     */
    @Transactional
    fun bulkUpload(jsonFile: InputStream): BulkUploadResponse {
        val root =
            try {
                objectMapper.readTree(jsonFile)
            } catch (e: JacksonException) {
                throw BusinessException(ErrorCode.INVALID_JSON_FORMAT, cause = e)
            }

        var saved = 0
        var skipped = 0
        for (node in findItemArray(root)) {
            val skeletonCsv = SKELETON_KEYS.firstNotNullOfOrNull { node.get(it) }?.asString()
            if (skeletonCsv.isNullOrBlank()) {
                skipped++
                continue
            }
            val metadata = node.get("metadata")?.takeIf { it.isObject }
            val skeleton =
                skeletonDataRepository.save(
                    SkeletonData(
                        skeletonData = skeletonCsv,
                        frameCount = metadata.intOrNull("frameCount") ?: countCsvFrames(skeletonCsv),
                        fps = metadata.doubleOrNull("fps") ?: FALLBACK_FPS,
                        resolution =
                            metadata?.get("resolution")?.asString()?.takeIf { it.isNotBlank() }
                                ?: FALLBACK_RESOLUTION,
                    ),
                )
            referenceModelRepository.save(
                ReferenceModel(
                    pitcherName = node.get("playerName")?.asString() ?: UNKNOWN_PITCHER,
                    pitchType = node.get("pitchType")?.asString() ?: DEFAULT_PITCH_TYPE,
                    sourceUrl = node.get("proId")?.asString(),
                    skeletonData = skeleton,
                ),
            )
            saved++
        }

        if (skipped > 0) {
            log.warn("골격 데이터가 없어 건너뛴 항목이 있습니다. saved={}, skipped={}", saved, skipped)
        }
        return BulkUploadResponse(
            savedCount = saved,
            skippedCount = skipped,
            message = "프로 선수 ${saved}명이 등록되었습니다." + if (skipped > 0) " (골격 데이터 없음 ${skipped}건 제외)" else "",
        )
    }

    @Transactional
    fun deleteAll() {
        // 순서가 중요하다. ReferenceModel이 SkeletonData를 참조하므로 참조하는 쪽을 먼저 지운다.
        referenceModelRepository.deleteAll()
        skeletonDataRepository.deleteAll()
    }

    /**
     * 업로드된 파일에서 골격 CSV를 꺼낸다.
     *
     * JSON으로 감싸여 있으면 skeleton_data 값을, 아니면 원문 전체를 CSV로 본다.
     * JSON 파싱 실패는 정상 경로(CSV 원문)이지만, 원인을 완전히 삼키면
     * 파일 형식 문제를 영영 알 수 없으므로 로그는 남긴다.
     */
    private fun extractSkeletonCsv(
        raw: String,
        fileName: String?,
    ): String =
        try {
            val root = objectMapper.readTree(raw)
            when {
                root.isArray && !root.isEmpty -> root.get(0)?.get("skeleton_data")?.asString() ?: raw
                root.isObject -> root.get("skeleton_data")?.asString() ?: raw
                else -> raw
            }
        } catch (e: JacksonException) {
            log.warn("골격 파일을 JSON으로 해석하지 못해 원본을 CSV로 사용합니다: {}", fileName, e)
            raw
        }

    private fun findItemArray(root: JsonNode): JsonNode =
        when {
            root.isArray -> root
            root.isObject ->
                ITEM_ARRAY_KEYS
                    .firstNotNullOfOrNull { key -> root.get(key)?.takeIf { it.isArray } }
                    ?: throw BusinessException(ErrorCode.INVALID_JSON_FORMAT, "JSON 객체에서 항목 배열을 찾지 못했습니다.")
            else -> throw BusinessException(ErrorCode.INVALID_JSON_FORMAT)
        }

    /** metadata에 frameCount가 없을 때 CSV 줄 수로 추정한다(헤더 1줄 제외). */
    private fun countCsvFrames(csv: String): Int {
        val lines = csv.split("\n", "\r\n").filter { it.isNotBlank() }
        return if (lines.isNotEmpty()) lines.size - 1 else 0
    }

    private fun JsonNode?.intOrNull(field: String): Int? = this?.get(field)?.takeIf { it.isNumber }?.asInt()

    private fun JsonNode?.doubleOrNull(field: String): Double? = this?.get(field)?.takeIf { it.isNumber }?.asDouble()
}
