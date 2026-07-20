package com.aas.app.vehicle

/**
 * AAS copy of every explicit WriteEntry declared in the supplied BYDMate
 * WriteAllowlist.kt. This list intentionally does not invent or scan unknown
 * vehicle identifiers. The 123 entries mentioned in the source comments live
 * in a separate competitor-actions.json and are not present in the supplied file.
 */
internal object BydWriteAllowlist {
    data class Entry(
        val actionName: String,
        val device: Int,
        val fid: Int,
        val valueMin: Int,
        val valueMax: Int,
        val category: String,
        val validated: Boolean,
        val source: String,
    ) {
        val fixedValue: Int? get() = if (valueMin == valueMax) valueMin else null
        fun accepts(value: Int): Boolean = value in valueMin..valueMax
    }

    val entries: List<Entry> = listOf(
        Entry("ac_on", 1000, 501219364, 1, 1, "climate", true, "live-leopard3-2026-07-03"),
        Entry("ac_off", 1000, 501219364, 0, 0, "climate", true, "live-leopard3-2026-07-03"),
        Entry("ac_auto_on", 1000, 501219352, 0, 0, "climate", true, "live-leopard3-2026-07-07"),
        Entry("ac_auto_off", 1000, 501219352, 1, 1, "climate", true, "live-leopard3-2026-07-07"),
        Entry("ac_temp_main", 1000, 501219368, 16, 30, "climate", true, "live-leopard3-2026-05-28"),
        Entry("ac_cycle_inner", 1000, 501219355, 1, 1, "climate", true, "live-leopard3-2026-05-28"),
        Entry("ac_cycle_outer", 1000, 501219355, 0, 0, "climate", true, "live-leopard3-2026-06-28"),
        Entry("window_driver_open", 1001, 1125122104, 1, 1, "windows", true, "live-leopard3-2026-05-28"),
        Entry("window_driver_close", 1001, 1125122104, 2, 2, "windows", true, "live-leopard3-2026-05-28"),
        Entry("window_passenger_open", 1001, 1125122107, 1, 1, "windows", true, "live-leopard3-2026-05-28"),
        Entry("window_passenger_close", 1001, 1125122107, 2, 2, "windows", true, "live-leopard3-2026-05-28"),
        Entry("window_driver_pos", 1001, 1276219408, 0, 100, "windows", true, "live-leopard3-2026-05-28"),
        Entry("window_passenger_pos", 1001, 1276219424, 0, 100, "windows", true, "live-leopard3-2026-05-28"),
        Entry("window_rear_left_pos", 1001, 1276219416, 0, 100, "windows", true, "live-leopard3-2026-05-28"),
        Entry("window_rear_right_pos", 1001, 1276219432, 0, 100, "windows", true, "live-leopard3-2026-05-28"),
        Entry("sunroof_open", 1001, 1125122056, 1, 1, "sunroof", true, "live-leopard3-2026-05-28"),
        Entry("sunroof_close", 1001, 1125122056, 2, 2, "sunroof", true, "live-leopard3-2026-05-28"),
        Entry("sunroof_tilt", 1001, 1125122056, 3, 3, "sunroof", true, "live-leopard3-2026-05-28"),
        Entry("sunroof_stop", 1001, 1125122056, 4, 4, "sunroof", true, "live-leopard3-2026-05-28"),
        Entry("sunroof_updip", 1001, 1125122056, 5, 5, "sunroof", true, "live-leopard3-2026-05-28"),
        Entry("sunroof_comfort", 1001, 1125122056, 6, 6, "sunroof", true, "live-leopard3-2026-05-28"),
        Entry("sunshade_open", 1001, 1125122060, 1, 1, "sunshade", true, "live-leopard3-2026-05-28"),
        Entry("sunshade_close", 1001, 1125122060, 2, 2, "sunshade", true, "live-leopard3-2026-05-28"),
        Entry("doors_unlock", 1001, 1276141590, 1, 1, "locks", true, "live-leopard3-2026-05-28"),
        Entry("doors_lock", 1001, 1276141590, 2, 2, "locks", true, "live-leopard3-2026-05-28"),
        Entry("interior_light_on", 1023, 1330643002, 2, 2, "lights", true, "live-leopard3-2026-05-29"),
        Entry("interior_light_off", 1023, 1330643002, 1, 1, "lights", true, "live-leopard3-2026-05-29"),
        Entry("ambient_light_on", 1023, 1069547536, 5, 5, "lights", true, "live-leopard3-2026-05-29"),
        Entry("ambient_light_off", 1023, 1069547536, 1, 1, "lights", true, "live-leopard3-2026-05-29"),
        Entry("drl_on", 1004, 1125122118, 1, 1, "lights", true, "live-leopard3-2026-05-29"),
        Entry("drl_off", 1004, 1125122118, 2, 2, "lights", true, "live-leopard3-2026-05-29"),
        Entry("defrost_rear_on", 1000, 501219357, 1, 1, "climate", true, "live-leopard3-2026-05-29"),
        Entry("defrost_rear_off", 1000, 501219357, 0, 0, "climate", true, "live-leopard3-2026-05-29"),
        Entry("driver_seat_heat_switch", 1000, 1276248084, 0, 2, "seats", true, "live-leopard3-2026-07-01"),
        Entry("driver_seat_heat_level", 1000, 1276252180, 1, 5, "seats", true, "live-leopard3-2026-06-29"),
        Entry("passenger_seat_heat_switch", 1000, 1276248092, 0, 2, "seats", true, "live-leopard3-2026-07-01"),
        Entry("passenger_seat_heat_level", 1000, 1276252188, 1, 5, "seats", true, "live-leopard3-2026-06-29"),
        Entry("driver_seat_vent_switch", 1000, 1276248080, 0, 2, "seats", true, "live-leopard3-2026-07-01"),
        Entry("driver_seat_vent_level", 1000, 1276252176, 1, 5, "seats", true, "live-leopard3-2026-06-29"),
        Entry("passenger_seat_vent_switch", 1000, 1276248088, 0, 2, "seats", true, "live-leopard3-2026-07-01"),
        Entry("passenger_seat_vent_level", 1000, 1276252184, 1, 5, "seats", true, "live-leopard3-2026-06-29"),
        Entry("front_trunk_open", 1001, 1276182560, 1, 1, "trunk", true, "live-leopard3-2026-06-29"),
        Entry("front_trunk_close", 1001, 1276182560, 3, 3, "trunk", true, "live-leopard3-2026-06-29"),
        Entry("fridge_mode", 1023, 850427920, 1, 3, "fridge", true, "live-leopard3-2026-06-29"),
        Entry("fridge_temp_cool", 1023, 850427928, 13, 25, "fridge", true, "live-leopard3-2026-06-29"),
        Entry("fridge_temp_heat", 1023, 850427928, 35, 50, "fridge", true, "live-leopard3-2026-06-29"),
        Entry("driver_seat_heat_fallback", 1001, 1125122068, 1, 6, "seats", false, "competitor-v80"),
        Entry("driver_seat_vent_fallback", 1001, 1125122064, 1, 6, "seats", false, "competitor-v80"),
        Entry("passenger_seat_heat_fallback", 1001, 1125122076, 1, 6, "seats", false, "competitor-v80"),
        Entry("passenger_seat_vent_fallback", 1001, 1125122072, 1, 6, "seats", false, "competitor-v80"),
    )

    private val byName = entries.associateBy { it.actionName.lowercase() }

    fun find(actionName: String): Entry? = byName[actionName.lowercase()]
}
