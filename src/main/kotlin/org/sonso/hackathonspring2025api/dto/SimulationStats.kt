package org.sonso.hackathonspring2025api.dto

// Результаты симуляции:
// - probsByPlace: для каждого спортсмена вероятность занять каждую позицию (индекс 0 соответствует 1-му месту)
// - pairTop2Probs: вероятность, что спортсмен i займёт 1-е, а спортсмен j – 2-е место
// - pairAllProbs: вероятность для любых двух позиций (например, P(Иван=1-е, Виталя=3-е))
data class SimulationStats(
    val probsByPlace: Array<DoubleArray>,
    val pairTop2Probs: Map<Pair<Int, Int>, Double>,
    val pairAllProbs: Map<PairKey, Double>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SimulationStats

        if (!probsByPlace.contentDeepEquals(other.probsByPlace)) return false
        if (pairTop2Probs != other.pairTop2Probs) return false
        if (pairAllProbs != other.pairAllProbs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = probsByPlace.contentDeepHashCode()
        result = 31 * result + pairTop2Probs.hashCode()
        result = 31 * result + pairAllProbs.hashCode()
        return result
    }
}