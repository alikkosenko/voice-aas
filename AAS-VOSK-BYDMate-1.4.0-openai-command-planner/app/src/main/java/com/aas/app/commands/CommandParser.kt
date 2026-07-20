package com.aas.app.commands

import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/** Bilingual RU/UK command parser with tolerant matching for Vosk hypotheses. */
class CommandParser {
    fun parse(raw: String): VoiceCommand? {
        val rawTechnical = raw.trim().lowercase(Locale.ROOT)
        Regex(
            "^(?:bydmate|byd mate|би вай ди мейт|бі вай ді мейт|" +
                "сервисная команда|техническая команда|сервісна команда|технічна команда)" +
                "\\s+([a-z0-9_]+)(?:\\s+(-?[0-9]+))?$"
        ).matchEntire(rawTechnical)?.let { match ->
            val actionName = match.groupValues[1]
            val value = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull()
            return VoiceCommand.ExecuteAllowlistedAction(actionName, value)
        }

        val text = normalize(raw)
        val words = text.split(' ').filter { it.isNotBlank() }
        val number = extractNumber(text)

        fun has(vararg variants: String): Boolean =
            variants.any { fuzzyPhrase(words, normalize(it).split(' ')) }

        fun offWord(): Boolean = words.any {
            it.startsWith("выключ") || it.startsWith("отключ") ||
                it.startsWith("вимк") || it.startsWith("відключ")
        }

        fun onWord(): Boolean = words.any {
            it.startsWith("включ") || it.startsWith("запуст") ||
                it.startsWith("увімк") || it.startsWith("ввімк")
        }

        // YouTube / ReVanced search. The words after "YouTube" are treated as
        // the complete search query: "ютуб linkin park", "YouTube огляд BYD".
        // This is intentionally parsed before generic multimedia commands.
        parseYoutubeCommand(text)?.let { return it }

        // Navigation with natural Russian and Ukrainian prefixes.
        val navPrefixes = listOf(
            "построй маршрут до", "построй маршрут в",
            "проложи маршрут до", "проложи маршрут в",
            "поехали до", "поехали в", "поедем до", "поедем в",
            "маршрут до", "маршрут в", "веди до", "веди в",
            "побудуй маршрут до", "побудуй маршрут у", "побудуй маршрут в",
            "проклади маршрут до", "проклади маршрут у", "проклади маршрут в",
            "поїхали до", "поїхали у", "поїхали в",
            "їдемо до", "їдемо у", "їдемо в",
            "маршрут до", "маршрут у", "веди до", "веди у"
        )
        for (prefix in navPrefixes) {
            if (text.startsWith("$prefix ")) {
                val destination = text.removePrefix(prefix).trim()
                if (destination.isNotEmpty()) return VoiceCommand.NavigateTo(destination)
            }
        }

        if (has(
                "повтори последний маршрут", "повторить последний маршрут", "предыдущий маршрут",
                "повтори останній маршрут", "повторити останній маршрут", "попередній маршрут"
            )
        ) return VoiceCommand.RepeatNavigation

        when {
            has("поехали домой", "маршрут домой", "веди домой", "поїхали додому", "маршрут додому", "веди додому") ->
                return VoiceCommand.NavigateTo(if (has("додому")) "дім" else "домой")
            has("поехали на работу", "маршрут на работу", "веди на работу", "поїхали на роботу", "маршрут на роботу", "веди на роботу") ->
                return VoiceCommand.NavigateTo(if (has("роботу")) "робота" else "работа")
            has("поехали в офис", "маршрут в офис", "веди в офис", "поїхали в офіс", "маршрут в офіс", "веди в офіс") ->
                return VoiceCommand.NavigateTo(if (has("офіс")) "офіс" else "офис")
            has("ближайшая зарядка", "найди зарядку", "зарядка рядом", "найближча зарядка", "знайди зарядку", "зарядка поруч") ->
                return VoiceCommand.NavigateTo(if (has("найближча", "знайди", "поруч")) "зарядна станція поруч" else "зарядная станция рядом")
            has("ближайшая заправка", "найди заправку", "азс рядом", "заправка рядом", "найближча заправка", "знайди заправку", "азс поруч", "заправка поруч") ->
                return VoiceCommand.NavigateTo(if (has("найближча", "знайди", "поруч")) "АЗС поруч" else "АЗС рядом")
            has("ближайшее сто", "найди сто", "сто рядом", "автосервис рядом", "найближче сто", "знайди сто", "сто поруч", "автосервіс поруч") ->
                return VoiceCommand.NavigateTo(if (has("найближче", "знайди", "поруч")) "СТО поруч" else "СТО рядом")
            has("ближайший шиномонтаж", "найди шиномонтаж", "шиномонтаж рядом", "найближчий шиномонтаж", "знайди шиномонтаж", "шиномонтаж поруч") ->
                return VoiceCommand.NavigateTo(if (has("найближчий", "знайди", "поруч")) "шиномонтаж поруч" else "шиномонтаж рядом")
            has("ближайшая парковка", "найди парковку", "парковка рядом", "найближча парковка", "знайди парковку", "парковка поруч") ->
                return VoiceCommand.NavigateTo(if (has("найближча", "знайди", "поруч")) "парковка поруч" else "парковка рядом")
        }

        if (has("блютус", "блютуз", "bluetooth")) return VoiceCommand.SetBluetooth(!offWord())
        if (has("вай фай", "вайфай", "wi fi", "wifi")) return VoiceCommand.SetWifi(!offWord())

        if (has("авто климат", "автоклимат", "автоматический климат", "автоматичний клімат", "авто режим", "автоматичний режим клімату")) {
            return if (offWord()) VoiceCommand.ClimateAutoOff else VoiceCommand.ClimateAutoOn
        }

        if (has("климат", "клімат", "кондиционер", "кондиціонер")) {
            return if (offWord()) VoiceCommand.ClimateOff else VoiceCommand.ClimateOn
        }

        if (has("температура", "температуру", "градусы", "градусів", "встанови температуру", "установи температуру") && number in 16..30) {
            return VoiceCommand.SetTemperature(number!!)
        }

        if (has("рециркуляция", "рециркуляцію", "рециркуляція", "внутренний воздух", "внутрішнє повітря")) {
            return if (has("улица", "улицы", "наружный", "зовнішнє", "з вулиці", "вулиця")) {
                VoiceCommand.RecirculationOuter
            } else VoiceCommand.RecirculationInner
        }
        if (has("забор воздуха", "забір повітря", "наружный воздух", "зовнішнє повітря", "повітря з вулиці")) {
            return VoiceCommand.RecirculationOuter
        }

        if (has(
                "обогрев заднего стекла", "задний обогрев", "обогрев зеркал", "подогрев зеркал",
                "обігрів заднього скла", "задній обігрів", "обігрів дзеркал", "підігрів дзеркал"
            )
        ) return if (offWord()) VoiceCommand.RearDefrostOff else VoiceCommand.RearDefrostOn

        if (has("шторка", "шторку", "сонцезахисна шторка", "шторка люка")) {
            val open = has("открой", "открыть", "відкрий", "відкрити") ||
                !has("закрой", "закрыть", "закрий", "закрити")
            return VoiceCommand.SetSunshade(open)
        }

        if (has("люк", "панорама", "панорамная крыша", "панорамний дах")) {
            return when {
                has("стоп", "останови", "остановить", "зупини", "зупинити") -> VoiceCommand.SetSunroof(VoiceCommand.SunroofAction.STOP)
                has("комфорт", "без сквозняка", "без протягу") -> VoiceCommand.SetSunroof(VoiceCommand.SunroofAction.COMFORT)
                has("проветривание", "проветрить", "вентиляция", "провітрювання", "провітрити") -> VoiceCommand.SetSunroof(VoiceCommand.SunroofAction.VENT)
                has("приподними", "наклон", "нахили", "нахил", "підніми") -> VoiceCommand.SetSunroof(VoiceCommand.SunroofAction.TILT)
                has("закрой", "закрыть", "закрий", "закрити") -> VoiceCommand.SetSunroof(VoiceCommand.SunroofAction.CLOSE)
                else -> VoiceCommand.SetSunroof(VoiceCommand.SunroofAction.OPEN)
            }
        }

        if (has("замки", "двери", "двері") && has("закрой", "запри", "заблокируй", "закрий", "замкни", "заблокуй")) {
            return VoiceCommand.SetDoorLocks(true)
        }
        if (has("замки", "двери", "двері") && has("открой", "отопри", "разблокируй", "відкрий", "відімкни", "розблокуй")) {
            return VoiceCommand.SetDoorLocks(false)
        }

        if (has("свет в салоне", "салонный свет", "світло в салоні", "салонне світло")) {
            return VoiceCommand.SetInteriorLight(!offWord())
        }
        if (has("подсветка салона", "атмосферная подсветка", "ambient", "підсвітка салону", "підсвічування салону", "атмосферне підсвічування")) {
            return VoiceCommand.SetAmbientLight(!offWord())
        }
        if (has("дневные огни", "дхо", "денні вогні", "денні ходові вогні")) {
            return VoiceCommand.SetDaytimeRunningLights(!offWord())
        }

        if (has("передний багажник", "передній багажник", "франк", "frunk")) {
            val open = has("открой", "открыть", "відкрий", "відкрити") ||
                !has("закрой", "закрыть", "закрий", "закрити")
            return VoiceCommand.SetFrontTrunk(open)
        }
        if (has("багажник", "задний багажник", "задній багажник", "багажник сзади", "багажник позаду", "крышка багажника", "кришка багажника", "trunk")) {
            val open = has("открой", "открыть", "відкрий", "відкрити", "подними", "підніми") ||
                !has("закрой", "закрыть", "закрий", "закрити", "опусти", "опустити")
            return VoiceCommand.SetRearTrunk(open)
        }

        if (has(
                "обогрев руля", "подогрев руля", "теплый руль", "согрей руль", "нагреть руль",
                "обігрів керма", "підігрів керма", "тепле кермо", "зігрій кермо", "нагрій кермо"
            )
        ) {
            val level = when {
                offWord() -> 0
                has("максимум", "максимальный", "максимальная", "максимальний", "максимальна", "на максимум") -> 3
                has("средний", "средняя", "середній", "середня", "на средний", "на середній") -> 2
                has("минимум", "минимальный", "минимальная", "мінімум", "мінімальний", "мінімальна", "на минимум", "на мінімум") -> 1
                else -> (number ?: 1).coerceIn(1, 3)
            }
            return VoiceCommand.SetSteeringWheelHeating(level)
        }

        if (has("холодильник", "автохолодильник", "холодильника", "холодильнику")) {
            if (offWord()) return VoiceCommand.SetFridge(false)
            val target = when {
                has("нагрев", "подогрев", "греть", "обогрев", "нагрів", "підігрів", "гріти") && number == null -> 35
                has("охлаждение", "охлаждать", "холод", "охолодження", "охолоджувати") && number == null -> 3
                else -> number?.let { n -> if (has("минус", "минусовая", "мінус")) -n else n }
            }
            return VoiceCommand.SetFridge(true, target)
        }

        // Seats: only treat generic "обдув" as seat ventilation when a seat is named.
        val explicitAllSeats = has("все сиденья", "всех сидений", "оба сиденья", "всі сидіння", "усі сидіння", "всіх сидінь", "усіх сидінь", "обидва сидіння")
        val explicitPassengerSeat = has(
            "пассажир", "пассажирское", "пассажирского", "правое сиденье",
            "пасажир", "пасажирське", "пасажирського", "праве сидіння"
        )
        val explicitDriverSeat = has(
            "водитель", "водительское", "водительского", "левое сиденье",
            "водій", "водійське", "водійського", "ліве сидіння"
        )
        val seat = when {
            explicitAllSeats -> VoiceCommand.Seat.ALL
            explicitPassengerSeat -> VoiceCommand.Seat.PASSENGER
            else -> VoiceCommand.Seat.DRIVER
        }
        val seatMentioned = has("сиденье", "сиденья", "сидений", "сидіння", "сидінь", "кресло", "кресла", "крісло")
        val seatContext = explicitAllSeats || explicitPassengerSeat || explicitDriverSeat || seatMentioned
        val heating = has("обогрев", "подогрев", "подогрей", "согрей", "підігрів", "обігрів", "підігрій", "зігрій", "грелка")
        val ventilation = has("вентиляция", "вентиляцию", "вентиляцію", "вентиляція", "обдув", "проветри сиденье", "провітри сидіння")
        // User-facing seat levels are normalized to the three physical stages
        // exposed by BYD climate UIs. Zero explicitly disables the function.
        val seatLevel = when {
            offWord() || number == 0 -> 0
            has("максимум", "максимальный", "максимальная", "максимальний", "максимальна", "на максимум") -> 3
            has("средний", "средняя", "середній", "середня", "на средний", "на середній") -> 2
            has("минимум", "минимальный", "минимальная", "мінімум", "мінімальний", "мінімальна", "на минимум", "на мінімум") -> 1
            else -> (number ?: 1).coerceIn(1, 3)
        }
        if (seatContext && heating) return VoiceCommand.SetSeatHeating(seat, seatLevel)
        if (seatContext && ventilation) return VoiceCommand.SetSeatVentilation(seat, seatLevel)

        if (has("вентилятор", "обдув салона", "обдув", "вентилятор салону", "обдув салону") && number in 1..7) {
            return VoiceCommand.SetFanLevel(number!!)
        }

        val window = when {
            has("все окна", "все стекла", "всі вікна", "усі вікна") -> VoiceCommand.Window.ALL
            has("передние окна", "передние стекла", "оба передних окна", "передні вікна", "обидва передні вікна") -> VoiceCommand.Window.FRONT
            has("задние окна", "задние стекла", "оба задних окна", "задні вікна", "обидва задні вікна") -> VoiceCommand.Window.REAR
            has("заднее левое", "заднее лево", "заднее левое окно", "заднє ліве", "заднє ліве вікно") -> VoiceCommand.Window.REAR_LEFT
            has("заднее правое", "заднее право", "заднее правое окно", "заднє праве", "заднє праве вікно") -> VoiceCommand.Window.REAR_RIGHT
            has("переднее правое", "правое окно", "пассажирское окно", "окно пассажира", "пасажирське вікно", "праве вікно") -> VoiceCommand.Window.PASSENGER
            has("переднее левое", "левое окно", "водительское окно", "окно водителя", "водійське вікно", "ліве вікно") -> VoiceCommand.Window.DRIVER
            has("окно", "стекло", "вікно", "скло") -> VoiceCommand.Window.DRIVER
            else -> null
        }
        if (window != null) {
            if (number in 0..100 && has("процент", "процентов", "процента", "відсоток", "відсотків", "відсотки", "положение", "позиция", "положення")) {
                return VoiceCommand.SetWindowPosition(window, number!!)
            }
            if (has("открой", "открыть", "опусти", "відкрий", "відкрити", "опусти")) return VoiceCommand.SetWindow(window, true)
            if (has("закрой", "закрыть", "подними", "закрий", "закрити", "підніми")) return VoiceCommand.SetWindow(window, false)
        }

        if (has("заряд", "батарея", "батареи", "заряду", "скільки заряду", "рівень заряду")) return VoiceCommand.QuerySoc
        if (has("запас хода", "дальность", "пробег", "запас ходу", "залишок ходу", "дальність")) return VoiceCommand.QueryRange
        if (has("шины", "шинах", "давление", "тиск", "колеса", "шини", "тиск у шинах")) return VoiceCommand.QueryTires

        // Relative forms must be checked before the generic "громкость/гучність"
        // matcher: fuzzy Vosk matching can otherwise interpret "громче на три" as
        // an absolute SetVolume(3).
        val volumeUpRequested = words.any { word ->
            word == "громче" || word.startsWith("прибав") || word.startsWith("увелич") ||
                word.startsWith("збільш") || word.startsWith("гучніш")
        }
        if (volumeUpRequested) {
            return number?.let { VoiceCommand.AdjustVolume(kotlin.math.abs(it).coerceIn(1, 20)) }
                ?: VoiceCommand.VolumeUp
        }
        val volumeDownRequested = words.any { word ->
            word == "тише" || word.startsWith("убав") || word.startsWith("уменьш") ||
                word.startsWith("зменш") || word.startsWith("тихіш")
        }
        if (volumeDownRequested) {
            return number?.let { VoiceCommand.AdjustVolume(-kotlin.math.abs(it).coerceIn(1, 20)) }
                ?: VoiceCommand.VolumeDown
        }
        val volumeMentioned = has(
            "громкость", "уровень громкости", "звук",
            "гучність", "рівень гучності"
        )
        if (volumeMentioned && number in 0..100) {
            val percentage = has(
                "процент", "процента", "процентов", "в процентах",
                "відсоток", "відсотки", "відсотків", "у відсотках"
            )
            return VoiceCommand.SetVolume(number!!, percentage)
        }
        if (has("без звука", "мьют", "тишина", "без звуку", "тиша") || (has("звук") && offWord())) return VoiceCommand.Mute
        if (has("звук") && onWord()) return VoiceCommand.Unmute

        if (has("пауза", "стоп музыка", "продолжи", "продовж", "відтворення", "воспроизведение")) return VoiceCommand.PlayPause
        if (has("следующий", "следующая", "дальше", "наступний", "наступна", "далі")) return VoiceCommand.NextTrack
        if (has("предыдущий", "предыдущая", "назад", "попередній", "попередня")) return VoiceCommand.PreviousTrack

        if (has("навигация", "навигатор", "карта", "карты", "навігація", "навігатор", "мапа", "мапи")) return VoiceCommand.OpenNavigator
        if (has("ютуб", "youtube", "ю туб")) return VoiceCommand.OpenYoutube
        if (has("радио", "радіо", "радиостанция", "радіостанція", "fm", "эф эм", "еф ем")) return VoiceCommand.OpenRadio
        if (has("музыка", "музыку", "плеер", "музика", "музику")) return VoiceCommand.OpenMusic
        if (has("домой", "главный экран", "додому", "головний екран", "home")) return VoiceCommand.GoHome

        return null
    }

    private fun parseYoutubeCommand(text: String): VoiceCommand? {
        val youtubeTokens = listOf(
            "youtube", "ютубе", "ютубі", "ютюбе", "ютюбі", "ютьюбе", "ютьюбі",
            "ютуб", "ютюб", "ютьюб", "ю туб"
        )
        val openOnlyWords = setOf(
            "открой", "открыть", "запусти", "запустить",
            "відкрий", "відкрити", "запусти", "запустити"
        )
        val removableQueryPrefixes = listOf(
            "и найди видео", "и найди", "и включи видео", "и включи",
            "найди видео", "найди", "найти", "поищи", "поиск", "покажи", "включи видео", "включи",
            "і знайди відео", "і знайди", "і увімкни відео", "і увімкни",
            "знайди відео", "знайди", "пошукай", "пошук", "покажи", "увімкни відео", "увімкни"
        )

        for (token in youtubeTokens.sortedByDescending { it.length }) {
            if (text == token) return VoiceCommand.OpenYoutube
            if (text.startsWith("$token ")) {
                var query = text.removePrefix(token).trim()
                if (query in openOnlyWords) return VoiceCommand.OpenYoutube
                removableQueryPrefixes.firstOrNull { query == it || query.startsWith("$it ") }?.let { prefix ->
                    query = query.removePrefix(prefix).trim()
                }
                return if (query.isNotEmpty()) VoiceCommand.SearchYoutube(query) else VoiceCommand.OpenYoutube
            }
        }

        // Also support natural word order: "найди на ютубе ..." and
        // "открой YouTube ..." in Russian and Ukrainian.
        val tokenPattern = "(?:youtube|ютуб(?:е|і)?|ютюб(?:е|і)?|ютьюб(?:е|і)?|ю\\s+туб)"
        val requestPattern = Regex(
            "^(?:открой|открыть|запусти|запустить|найди|найти|поищи|покажи|включи|" +
                "відкрий|відкрити|запусти|запустити|знайди|пошукай|покажи|увімкни)" +
                "\\s+(?:(?:на|в|у)\\s+)?$tokenPattern\\s+(.+)$"
        )
        requestPattern.matchEntire(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return VoiceCommand.SearchYoutube(it)
        }

        return null
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace('’', '\'')
        .replace("'", "")
        .replace(Regex("[^а-яіїєґa-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun fuzzyPhrase(textWords: List<String>, phraseWords: List<String>): Boolean {
        if (phraseWords.isEmpty() || textWords.isEmpty()) return false
        if (phraseWords.size == 1) return textWords.any { fuzzyWord(it, phraseWords[0]) }

        for (start in 0..(textWords.size - phraseWords.size).coerceAtLeast(0)) {
            if (start + phraseWords.size > textWords.size) break
            var ok = true
            for (i in phraseWords.indices) {
                if (!fuzzyWord(textWords[start + i], phraseWords[i])) {
                    ok = false
                    break
                }
            }
            if (ok) return true
        }
        return false
    }

    private fun fuzzyWord(actual: String, expected: String): Boolean {
        if (actual == expected) return true
        if (actual.length >= 4 && expected.length >= 4 &&
            (actual.startsWith(expected.take(4)) || expected.startsWith(actual.take(4)))) return true

        val maxDistance = when {
            min(actual.length, expected.length) >= 8 -> 2
            min(actual.length, expected.length) >= 4 -> 1
            else -> 0
        }
        if (abs(actual.length - expected.length) > maxDistance) return false
        return levenshtein(actual, expected) <= maxDistance
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = min(
                    min(current[j] + 1, previous[j + 1] + 1),
                    previous[j] + if (a[i] == b[j]) 0 else 1
                )
            }
            previous = current
        }
        return previous[b.length]
    }

    private fun extractNumber(text: String): Int? {
        Regex("\\b([0-9]{1,3})\\b").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val words = text.split(' ')
        val units = mapOf(
            "ноль" to 0, "нуль" to 0,
            "один" to 1, "одна" to 1, "первый" to 1, "первий" to 1, "перший" to 1, "перша" to 1,
            "два" to 2, "две" to 2, "второй" to 2, "вторий" to 2, "другий" to 2, "друга" to 2,
            "три" to 3, "третий" to 3, "третій" to 3, "третя" to 3,
            "четыре" to 4, "чотири" to 4,
            "пять" to 5,
            "шесть" to 6, "шість" to 6,
            "семь" to 7, "сім" to 7,
            "восемь" to 8, "вісім" to 8,
            "девять" to 9
        )
        val direct = mapOf(
            "десять" to 10,
            "одиннадцать" to 11, "одинадцять" to 11,
            "двенадцать" to 12, "дванадцять" to 12,
            "тринадцать" to 13, "тринадцять" to 13,
            "четырнадцать" to 14, "чотирнадцять" to 14,
            "пятнадцать" to 15, "пятнадцять" to 15,
            "шестнадцать" to 16, "шістнадцять" to 16,
            "семнадцать" to 17, "сімнадцять" to 17,
            "восемнадцать" to 18, "вісімнадцять" to 18,
            "девятнадцать" to 19, "девятнадцять" to 19,
            "двадцать" to 20, "двадцять" to 20,
            "тридцать" to 30, "тридцять" to 30,
            "сорок" to 40,
            "пятьдесят" to 50, "пятдесят" to 50,
            "шестьдесят" to 60, "шістдесят" to 60,
            "семьдесят" to 70, "сімдесят" to 70,
            "восемьдесят" to 80, "вісімдесят" to 80,
            "девяносто" to 90,
            "сто" to 100
        )
        words.forEachIndexed { index, word ->
            val base = direct[word] ?: return@forEachIndexed
            val unit = words.getOrNull(index + 1)?.let { units[it] } ?: 0
            if (base in 20..90 && unit > 0) return base + unit
        }
        words.forEach { direct[it]?.let { value -> return value } }
        words.forEach { units[it]?.let { value -> return value } }
        return null
    }
}
