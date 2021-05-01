package ithkuil.iv.gloss

sealed class FormattingOutcome

class Invalid(private val word: String, val message: String) : FormattingOutcome() {
    override fun toString(): String = word
}

sealed class Valid : FormattingOutcome() {
    abstract val prefixPunctuation: String
    abstract val postfixPunctuation: String
}

class ConcatenatedWords(
    val words: List<Word>,
    override val prefixPunctuation: String = "",
    override val postfixPunctuation: String = "",
) : Valid() {
    override fun toString(): String = words
        .joinToString(
            "-",
            prefix = prefixPunctuation,
            postfix = postfixPunctuation
        ) { it.toString() }
}

class Word(
    private val stressedGroups: List<String>,
    val stress: Stress,
    override val prefixPunctuation: String = "",
    override val postfixPunctuation: String = "",
    private val groups: List<String> = stressedGroups.map { it.clearStress() }
    // ^ Should never be specified; class delegation doesn't accept properties, only parameters
) : List<String> by groups, Valid() {

    override fun toString(): String {
        return stressedGroups.joinToString("", prefix = prefixPunctuation, postfix = postfixPunctuation)
    }

    fun stripSentencePrefix(): Pair<Word, Boolean> {
        val newGroups = when {
            size >= 3 && stressedGroups[0] == "ç" && stressedGroups[1] == "ë" -> drop(2)
            stressedGroups[0] == "ç" && stressedGroups[1].isVowel() -> drop(1)
            stressedGroups[0] == "çw" -> listOf("w") + drop(1)
            stressedGroups[0] == "çç" -> listOf("y") + drop(1)
            else -> return this to false
        }
        return Word(newGroups, stress, prefixPunctuation, postfixPunctuation) to true
    }

    val wordType by lazy { wordTypeOf(this.stripSentencePrefix().first) }

}

fun formatWord(fullWord: String): FormattingOutcome {

    if (fullWord.isEmpty()) return Invalid(fullWord, "Empty word")

    val punct = ".,?!:;⫶`\"*_"
    val punctuationRegex = "([$punct]*)([^$punct]+)([$punct]*)".toRegex()

    val (prefix, word, postfix) = punctuationRegex.matchEntire(fullWord)?.destructured
        ?: return Invalid(fullWord, "Unexpected punctuation")

    if ("-" in word) {
        return formatConcatenatedWords(word, prefix, postfix)
    }

    val clean = word.defaultFormWithStress()

    if (clean.last() == '\'') return Invalid(word, "Word ends in glottal stop")

    fun codepointString(c: Char): String {
        val codepoint = c.toInt()
            .toString(16)
            .toUpperCase()
            .padStart(4, '0')
        return "\"$c\" (U+$codepoint)"
    }

    val nonIthkuil = clean.filter { it.toString() !in ITHKUIL_CHARS }
    if (nonIthkuil.isNotEmpty()) {
        var message = nonIthkuil.map { codepointString(it) }.joinToString()

        if ("[qˇ^ʰ]".toRegex() in nonIthkuil) {
            message += " You might be writing in Ithkuil III. Try \"!gloss\" instead."
        }
        return Invalid(word, "Non-ithkuil characters detected: $message")
    }

    val stressedGroups = clean.splitGroups()

    for (group in stressedGroups) {
        if (group.isConsonant() xor group.isVowel()) continue
        return Invalid(word, "Unknown group: $group")
    }

    val stress = findStress(stressedGroups)

    when (stress) {
        Stress.INVALID_PLACE -> return Invalid(word, "Unrecognized stress placement")
        Stress.MARKED_DEFAULT -> return Invalid(word, "Marked default stress")
        Stress.DOUBLE_MARKED -> return Invalid(word, "Double-marked stress")
        else -> {
        }
    }

    return Word(stressedGroups, stress, prefixPunctuation = prefix, postfixPunctuation = postfix)
}

private fun formatConcatenatedWords(
    word: String,
    prefix: String,
    postfix: String
): FormattingOutcome {
    val words = word
        .split("-")
        .formatAll()
        .map {
            when (it) {
                is Word -> {
                    if (it.wordType != WordType.FORMATIVE) {
                        return Invalid(word, "Non-formative concatenated: ($it)")
                    }
                    it
                }
                is Invalid -> return Invalid(word, "${it.message} ($it)")
                is ConcatenatedWords -> return Invalid(word, "Nested concatenation! ($it)")
            }
        }
    return ConcatenatedWords(words, prefixPunctuation = prefix, postfixPunctuation = postfix)
}

fun List<String>.formatAll(): List<FormattingOutcome> = map { formatWord(it) }

enum class GroupingState {
    VOWEL,
    CONSONANT;

    fun switch(): GroupingState = when (this) {
        CONSONANT -> VOWEL
        VOWEL -> CONSONANT
    }

    companion object {
        fun start(first: Char): GroupingState = if (first in VOWELS) VOWEL else CONSONANT
    }

}

fun String.splitGroups(): List<String> {

    var index = 0
    var state = GroupingState.start(first())

    val groups = mutableListOf<String>()

    while (index <= lastIndex) {
        val group = when (state) {
            GroupingState.CONSONANT -> substring(index)
                .takeWhile { it in CONSONANTS }
            GroupingState.VOWEL -> substring(index)
                .takeWhile { it in VOWELS_AND_GLOTTAL_STOP }
        }

        state = state.switch()
        index += group.length
        groups += group
    }

    return groups
}

// Matches strings of the form "a", "ai", "a'" "a'i" and "ai'". Doesn't guarantee a valid vowelform.
fun String.isVowel() = when (length) {
    1 -> this[0] in VOWELS
    2 -> this[0] in VOWELS && this[1] in VOWELS_AND_GLOTTAL_STOP
    3 -> all { it in VOWELS_AND_GLOTTAL_STOP } && this[0] != '\'' && this.count { it == '\'' } == 1
    else -> false
}

fun String.isConsonant() = this.all { it in CONSONANTS }

val STRESSED_VOWELS = setOf('á', 'â', 'é', 'ê', 'í', 'î', 'ô', 'ó', 'û', 'ú')

fun String.hasStress(): Boolean? = when {
    this.getOrNull(1) in STRESSED_VOWELS -> null
    this[0] in STRESSED_VOWELS -> true
    else -> false
}

fun seriesAndForm(v: String): Pair<Int, Int> {
    return when (val index = VOWEL_FORMS.indexOfFirst { v isSameVowelAs it }) {
        -1 -> Pair(-1, -1)
        else -> Pair((index / 9) + 1, (index % 9) + 1)
    }
}

fun unglottalizeVowel(v: String): String {
    return v.filter { it != '\'' }
        .let {
            if (it.length == 2 && it[0] == it[1]) it.take(1) else it
        }
}

fun glottalizeVowel(v: String): String {
    return when (v.length) {
        1 -> "$v'$v"
        2 -> "${v[0]}'${v[1]}"
        else -> v
    }
}

//Deals with series three vowels
infix fun String.isSameVowelAs(s: String): Boolean = if ("/" in s) {
    s.split("/").any { it == this }
} else {
    s == this
}

fun String.substituteAll(substitutions: List<Pair<String, String>>) =
    substitutions.fold(this) { current, (allo, sub) ->
        current.replace(allo.toRegex(), sub)
    }

fun String.clearStress() = substituteAll(UNSTRESSED_FORMS)

fun String.defaultFormWithStress() = toLowerCase().substituteAll(ALLOGRAPHS)

fun String.defaultForm() = defaultFormWithStress().clearStress()

enum class Stress {
    ULTIMATE,
    PENULTIMATE,
    ANTEPENULTIMATE,
    MONOSYLLABIC,

    MARKED_DEFAULT,
    DOUBLE_MARKED,
    INVALID_PLACE;
}

fun findStress(groups: List<String>): Stress {
    val nuclei = groups.filter(String::isVowel)
        .map { it.removeSuffix("'") }
        .flatMap {
            if (it.length == 1 || it.clearStress() in DIPHTHONGS) {
                listOf(it)
            } else {
                it.toCharArray()
                    .map(Char::toString)
                    .filter { ch -> ch != "'" }
            }
        }

    val stresses = nuclei
        .reversed()
        .map { it.hasStress() ?: return Stress.INVALID_PLACE }

    val count = stresses.count { it }

    if (count > 1) return Stress.DOUBLE_MARKED
    if (nuclei.size == 1) {
        return if (count == 0) Stress.MONOSYLLABIC else Stress.MARKED_DEFAULT
    }

    return when (stresses.indexOfFirst { it }) {
        -1 -> Stress.PENULTIMATE
        0 -> Stress.ULTIMATE
        1 -> Stress.MARKED_DEFAULT
        2 -> Stress.ANTEPENULTIMATE
        else -> Stress.INVALID_PLACE
    }
}



