package com.youreyes.app.ui.functiontest

/** One row of the local Function-Test screen: an action plus its editable param JSON. */
data class FunctionTestRow(
    val action: String,
    val label: String,
    val paramsJson: String,
    val isRunning: Boolean = false,
    val resultText: String? = null,
    val isError: Boolean = false,
)

/**
 * Default param JSON per action, mirroring the request-body examples in
 * docs/project-context.md sections 6.4-6.12. Editable by the user before running.
 */
val DEFAULT_FUNCTION_TEST_ROWS: List<FunctionTestRow> = listOf(
    FunctionTestRow(
        action = "ride_quote",
        label = "Đặt xe - Báo giá",
        paramsJson = """{"current_location":{"lat":10.7769,"lng":106.7009},"destination":{"address":"Đại học Bách Khoa TP.HCM","lat":10.7721,"lng":106.6578}}""",
    ),
    FunctionTestRow(
        action = "ride_confirm",
        label = "Đặt xe - Xác nhận",
        paramsJson = """{"quote_id":"quote-123","confirm":true}""",
    ),
    FunctionTestRow(
        action = "music_play",
        label = "Nhạc - Phát",
        paramsJson = """{"song":"Nơi này có anh - Sơn Tùng M-TP","volume":60}""",
    ),
    FunctionTestRow(
        action = "music_stop",
        label = "Nhạc - Dừng",
        paramsJson = "{}",
    ),
    FunctionTestRow(
        action = "music_volume",
        label = "Nhạc - Âm lượng",
        paramsJson = """{"direction":"up"}""",
    ),
    FunctionTestRow(
        action = "navigation_start",
        label = "Điều hướng - Bắt đầu",
        paramsJson = """{"destination":{"address":"Bưu điện Thành phố Hồ Chí Minh"}}""",
    ),
    FunctionTestRow(
        action = "navigation_stop",
        label = "Điều hướng - Dừng",
        paramsJson = """{"navigation_id":"nav-123"}""",
    ),
    FunctionTestRow(
        action = "emergency_call",
        label = "Khẩn cấp",
        paramsJson = "{}",
    ),
    FunctionTestRow(
        action = "contact_call",
        label = "Gọi liên hệ",
        paramsJson = """{"name":"Em iu"}""",
    ),
    FunctionTestRow(
        action = "location_get",
        label = "Vị trí hiện tại",
        paramsJson = "{}",
    ),
    FunctionTestRow(
        action = "capabilities_get",
        label = "Kiểm tra quyền",
        paramsJson = "{}",
    ),
)
