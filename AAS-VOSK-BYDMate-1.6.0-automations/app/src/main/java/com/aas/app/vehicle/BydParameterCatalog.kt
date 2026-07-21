package com.aas.app.vehicle

/**
 * Clean-room catalog reconstructed from the observed BYD autoservice calls.
 *
 * These identifiers are known to be used by the inspected DiLink build, but
 * BYD may change them between hardware/firmware revisions. AAS therefore keeps
 * writes disabled by default and never scans or guesses unknown identifiers.
 */
internal object BydParameterCatalog {
    const val READ_INT = 5
    const val WRITE_INT = 6
    const val READ_FLOAT_BITS = 7

    data class Parameter(val device: Int, val fid: Int)

    val climatePower = Parameter(device = 1000, fid = 501_219_364)
    val climateTemperature = Parameter(device = 1000, fid = 501_219_368)
    val climateFanLevel = Parameter(device = 1000, fid = 501_219_340)
    val climateAuto = Parameter(device = 1000, fid = 501_219_352)
    val climateRecirculation = Parameter(device = 1000, fid = 501_219_355)
    val rearDefrost = Parameter(device = 1000, fid = 501_219_357)
    // Imported from BYDMate competitor-actions.json / CommandTranslator.
    // These channels are firmware-dependent and require an in-car verification.
    val frontDefrost = Parameter(device = 1000, fid = 501_219_362)
    val climateFlowOnly = Parameter(device = 1000, fid = 501_219_394)
    val steeringWheelHeat = Parameter(device = 1000, fid = 944_767_029)
    val fridgeMode = Parameter(device = 1023, fid = 850_427_920)
    val fridgeTemperature = Parameter(device = 1023, fid = 850_427_928)

    val sunroofCommand = Parameter(device = 1001, fid = 1_125_122_056)
    val sunshadeCommand = Parameter(device = 1001, fid = 1_125_122_060)
    val doorLocks = Parameter(device = 1001, fid = 1_276_141_590)
    val frontTrunk = Parameter(device = 1001, fid = 1_276_182_560)
    val rearTrunk = Parameter(device = 1001, fid = 1_125_122_080)

    val interiorLight = Parameter(device = 1023, fid = 1_330_643_002)
    val ambientLight = Parameter(device = 1023, fid = 1_069_547_536)
    val daytimeRunningLights = Parameter(device = 1004, fid = 1_125_122_118)

    val driverWindow = Parameter(device = 1001, fid = 1_276_219_408)
    val passengerWindow = Parameter(device = 1001, fid = 1_276_219_424)
    val rearLeftWindow = Parameter(device = 1001, fid = 1_276_219_416)
    val rearRightWindow = Parameter(device = 1001, fid = 1_276_219_432)

    // Alternate command-style window controls bundled with the inspected app.
    val driverWindowFallback = Parameter(device = 1001, fid = 1_125_122_104)
    val passengerWindowFallback = Parameter(device = 1001, fid = 1_125_122_107)
    val rearLeftWindowFallback = Parameter(device = 1001, fid = 1_125_122_112)
    val rearRightWindowFallback = Parameter(device = 1001, fid = 1_125_122_115)

    val driverSeatHeatSwitch = Parameter(device = 1000, fid = 1_276_248_084)
    val driverSeatHeatLevel = Parameter(device = 1000, fid = 1_276_252_180)
    val passengerSeatHeatSwitch = Parameter(device = 1000, fid = 1_276_248_092)
    val passengerSeatHeatLevel = Parameter(device = 1000, fid = 1_276_252_188)

    val driverSeatVentSwitch = Parameter(device = 1000, fid = 1_276_248_080)
    val driverSeatVentLevel = Parameter(device = 1000, fid = 1_276_252_176)
    val passengerSeatVentSwitch = Parameter(device = 1000, fid = 1_276_248_088)
    val passengerSeatVentLevel = Parameter(device = 1000, fid = 1_276_252_184)

    // Older/alternate autoservice path used as a fallback by the inspected app.
    val driverSeatHeatFallback = Parameter(device = 1001, fid = 1_125_122_068)
    val driverSeatVentFallback = Parameter(device = 1001, fid = 1_125_122_064)
    val passengerSeatHeatFallback = Parameter(device = 1001, fid = 1_125_122_076)
    val passengerSeatVentFallback = Parameter(device = 1001, fid = 1_125_122_072)

    val stateOfCharge = Parameter(device = 1014, fid = 1_246_777_400)
    val vehicleSpeed = Parameter(device = 1013, fid = -1_807_745_016)
    val gear = Parameter(device = 1011, fid = 555_745_336)
    val fanLevelReadOnly = Parameter(device = 1000, fid = 1_077_936_156)

    val tireFrontLeft = Parameter(device = 1016, fid = -1_728_052_956)
    val tireFrontRight = Parameter(device = 1016, fid = -1_728_052_952)
    val tireRearLeft = Parameter(device = 1016, fid = -1_728_052_948)
    val tireRearRight = Parameter(device = 1016, fid = -1_728_052_944)
}
