package com.capstone.backend.global.exception

/**
 * 비즈니스 규칙 위반을 나타내는 최상위 예외.
 *
 * 기존 코드는 의미가 다른 상황에 모두 IllegalArgumentException을 던지고 있었다.
 * "로그인 필요"(401), "중복 가입"(409), "남의 영상"(403)이 전부 같은 타입이라
 * 핸들러가 이들을 구분할 방법이 없었고, 결과적으로 전부 500으로 나갔다.
 *
 * 이제는 [errorCode]가 상태 코드까지 함께 들고 다니므로,
 * 예외를 던지는 쪽은 "무엇이 잘못됐는지"만 표현하면 된다.
 *
 * @param errorCode 어떤 규칙을 위반했는지
 * @param message 기본 메시지를 덮어쓸 때만 전달한다(예: 값 이름을 포함한 상세 안내)
 * @param cause 원인 예외가 있으면 전달한다. 로그에 스택트레이스를 남기기 위함이다.
 */
open class BusinessException(
    val errorCode: ErrorCode,
    override val message: String = errorCode.message,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
