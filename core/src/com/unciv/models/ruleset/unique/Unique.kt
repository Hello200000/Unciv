package com.unciv.models.ruleset.unique

import com.unciv.logic.battle.CombatAction
import com.unciv.logic.city.CityInfo
import com.unciv.models.stats.Stats
import com.unciv.models.translations.*
import com.unciv.logic.civilization.CivilizationInfo
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap


class Unique(val text: String, val sourceObjectType: UniqueTarget? = null, val sourceObjectName: String? = null) {
    /** This is so the heavy regex-based parsing is only activated once per unique, instead of every time it's called
     *  - for instance, in the city screen, we call every tile unique for every tile, which can lead to ANRs */
    val placeholderText = text.getPlaceholderText()
    val params = text.removeConditionals().getPlaceholderParameters()
    val type = UniqueType.values().firstOrNull { it.placeholderText == placeholderText }

    val stats: Stats by lazy {
        val firstStatParam = params.firstOrNull { Stats.isStats(it) }
        if (firstStatParam == null) Stats() // So badly-defined stats don't crash the entire game
        else Stats.parse(firstStatParam)
    }
    val conditionals: List<Unique> = text.getConditionals()

    val allParams = params + conditionals.flatMap { it.params }

    val isLocalEffect = params.contains("in this city")
    val isAntiLocalEffect = params.contains("in other cities")

    fun hasFlag(flag: UniqueFlag) = type != null && type.flags.contains(flag)

    fun isOfType(uniqueType: UniqueType) = uniqueType == type

    fun conditionalsApply(civInfo: CivilizationInfo? = null, city: CityInfo? = null): Boolean {
        return conditionalsApply(StateForConditionals(civInfo, city))
    }

    fun conditionalsApply(state: StateForConditionals?): Boolean {
        if (state == null) return conditionals.isEmpty() 
        for (condition in conditionals) {
            if (!conditionalApplies(condition, state)) return false
        }
        return true
    }

    private fun conditionalApplies(
        condition: Unique,
        state: StateForConditionals
    ): Boolean {

        fun ruleset() = state.civInfo!!.gameInfo.ruleSet
        val relevantUnitTile by lazy { state.attackedTile ?: state.unit?.getTile() }

        return when (condition.type) {
            UniqueType.ConditionalWar -> state.civInfo?.isAtWar() == true
            UniqueType.ConditionalNotWar -> state.civInfo?.isAtWar() == false
            UniqueType.ConditionalHappy ->
                state.civInfo != null && state.civInfo.statsForNextTurn.happiness >= 0
            UniqueType.ConditionalBetweenHappiness ->
                state.civInfo != null
                && condition.params[0].toInt() <= state.civInfo.happinessForNextTurn
                && state.civInfo.happinessForNextTurn < condition.params[1].toInt()
            UniqueType.ConditionalBelowHappiness -> 
                state.civInfo != null && state.civInfo.happinessForNextTurn < condition.params[0].toInt() 
            UniqueType.ConditionalGoldenAge ->
                state.civInfo != null && state.civInfo.goldenAges.isGoldenAge()
            UniqueType.ConditionalBeforeEra ->
                state.civInfo != null && state.civInfo.getEraNumber() < ruleset().eras[condition.params[0]]!!.eraNumber
            UniqueType.ConditionalStartingFromEra ->
                state.civInfo != null && state.civInfo.getEraNumber() >= ruleset().eras[condition.params[0]]!!.eraNumber
            UniqueType.ConditionalDuringEra ->
                state.civInfo != null && state.civInfo.getEraNumber() == ruleset().eras[condition.params[0]]!!.eraNumber
            UniqueType.ConditionalTech ->
                state.civInfo != null && state.civInfo.tech.isResearched(condition.params[0])
            UniqueType.ConditionalNoTech ->
                state.civInfo != null && !state.civInfo.tech.isResearched(condition.params[0])
            UniqueType.ConditionalPolicy ->
                state.civInfo != null && state.civInfo.policies.isAdopted(condition.params[0])
            UniqueType.ConditionalNoPolicy ->
                state.civInfo != null && !state.civInfo.policies.isAdopted(condition.params[0])

            UniqueType.ConditionalSpecialistCount ->
                state.cityInfo != null && state.cityInfo.population.getNumberOfSpecialists() >= condition.params[0].toInt()
            UniqueType.ConditionalFollowerCount ->
                state.cityInfo != null && state.cityInfo.religion.getFollowersOfMajorityReligion() >= condition.params[0].toInt()
            UniqueType.ConditionalWhenGarrisoned ->
                state.cityInfo != null && state.cityInfo.getCenterTile().militaryUnit != null && state.cityInfo.getCenterTile().militaryUnit!!.canGarrison()

            UniqueType.ConditionalVsCity -> state.theirCombatant?.matchesCategory("City") == true
            UniqueType.ConditionalVsUnits -> state.theirCombatant?.matchesCategory(condition.params[0]) == true
            UniqueType.ConditionalOurUnit ->
                state.ourCombatant?.matchesCategory(condition.params[0]) == true
                        || state.unit?.matchesFilter(condition.params[0]) == true
            UniqueType.ConditionalAttacking -> state.combatAction == CombatAction.Attack
            UniqueType.ConditionalDefending -> state.combatAction == CombatAction.Defend
            UniqueType.ConditionalAboveHP ->
                state.ourCombatant != null && state.ourCombatant.getHealth() > condition.params[0].toInt()
            UniqueType.ConditionalBelowHP ->
                state.ourCombatant != null && state.ourCombatant.getHealth() < condition.params[0].toInt()
            
            UniqueType.ConditionalInTiles ->
                relevantUnitTile?.matchesFilter(condition.params[0], state.civInfo) == true
            UniqueType.ConditionalFightingInTiles ->
                state.attackedTile?.matchesFilter(condition.params[0], state.civInfo) == true
            UniqueType.ConditionalInTilesAnd ->
                relevantUnitTile!=null && relevantUnitTile!!.matchesFilter(condition.params[0], state.civInfo)
                        && relevantUnitTile!!.matchesFilter(condition.params[1], state.civInfo)
            UniqueType.ConditionalVsLargerCiv -> {
                val yourCities = state.civInfo?.cities?.size ?: 1
                val theirCities = state.theirCombatant?.getCivInfo()?.cities?.size ?: 0
                yourCities < theirCities
            }
            UniqueType.ConditionalForeignContinent ->
                state.civInfo != null && state.unit != null
                        && (state.civInfo.cities.isEmpty()
                        || state.civInfo.getCapital().getCenterTile().getContinent()
                            != state.unit.getTile().getContinent()
                        )
            UniqueType.ConditionalAdjacentUnit ->
                state.civInfo != null && relevantUnitTile!!.neighbors.any {
                    it.militaryUnit != null
                    && it.militaryUnit!!.civInfo == state.civInfo    
                    && it.militaryUnit!!.matchesFilter(condition.params[0])
                }

            UniqueType.ConditionalNeighborTiles ->
                state.cityInfo != null &&
                        state.cityInfo.getCenterTile().neighbors.count {
                            it.matchesFilter(condition.params[2], state.civInfo)
                        } in (condition.params[0].toInt())..(condition.params[1].toInt())
            UniqueType.ConditionalNeighborTilesAnd ->
                state.cityInfo != null
                        && state.cityInfo.getCenterTile().neighbors.count {
                    it.matchesFilter(condition.params[2], state.civInfo) &&
                            it.matchesFilter(condition.params[3], state.civInfo)
                } in (condition.params[0].toInt())..(condition.params[1].toInt())

            UniqueType.ConditionalOnWaterMaps -> state.region?.continentID == -1
            UniqueType.ConditionalInRegionOfType -> state.region?.type == condition.params[0]
            UniqueType.ConditionalInRegionExceptOfType -> state.region?.type != condition.params[0]

            else -> false
        }
    }

    override fun toString() = if (type == null) "\"$text\"" else "$type (\"$text\")"
}


class UniqueMap: HashMap<String, ArrayList<Unique>>() {
    //todo Once all untyped Uniques are converted, this should be  HashMap<UniqueType, *>
    // For now, we can have both map types "side by side" each serving their own purpose,
    // and gradually this one will be deprecated in favor of the other
    fun addUnique(unique: Unique) {
        if (!containsKey(unique.placeholderText)) this[unique.placeholderText] = ArrayList()
        this[unique.placeholderText]!!.add(unique)
    }

    fun getUniques(placeholderText: String): Sequence<Unique> {
        return this[placeholderText]?.asSequence() ?: emptySequence()
    }

    fun getUniques(uniqueType: UniqueType) = getUniques(uniqueType.placeholderText)

    fun getAllUniques() = this.asSequence().flatMap { it.value.asSequence() }
}

/** DOES NOT hold untyped uniques! */
class UniqueMapTyped: EnumMap<UniqueType, ArrayList<Unique>>(UniqueType::class.java) {
    fun addUnique(unique: Unique) {
        if(unique.type==null) return
        if (!containsKey(unique.type)) this[unique.type] = ArrayList()
        this[unique.type]!!.add(unique)
    }

    fun getUniques(uniqueType: UniqueType): Sequence<Unique> =
        this[uniqueType]?.asSequence() ?: sequenceOf()
}


// Will probably be allowed to be used as a conditional when I get the motivation to work on that -xlenstra
class TemporaryUnique() {

    constructor(uniqueObject: Unique, turns: Int) : this() {
        unique = uniqueObject.text
        turnsLeft = turns
    }

    var unique: String = ""

    @delegate:Transient
    val uniqueObject: Unique by lazy { Unique(unique) }

    var turnsLeft: Int = 0
}