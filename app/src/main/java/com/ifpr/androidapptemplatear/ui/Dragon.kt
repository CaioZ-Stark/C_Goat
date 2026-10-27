package com.ifpr.androidapptemplatear.ui

import com.ifpr.androidapptemplatear.R

data class Move(val name: String, val power: Int, val accuracy: Int = 100)

data class Dragon(
    val name: String,
    val glb: String,        // nome do arquivo em assets
    val maxHp: Int,
    val moves: List<Move>
)

object Dragons {
    val all = listOf(
        Dragon("Dragão da Montanha", "mountain_dragon.glb", 120, listOf(
            Move("Garra", 18), Move("Rugido sísmico", 26, 85),
            Move("Cauda", 14), Move("Bola de fogo", 32, 70)
        )),
        Dragon("Dragão Ver2", "dragon_ver2.glb", 100, listOf(
            Move("Mordida", 20), Move("Sopro de fogo", 28, 85),
            Move("Asa cortante", 16), Move("Investida", 24, 90)
        )),
        // TROQUE pelo nome do seu terceiro modelo em assets
        Dragon("Dragão Dark", "charcoal_black_dark_dragon.glb", 110, listOf(
            Move("Arranhão", 15), Move("Raio", 30, 75),
            Move("Cauda", 16), Move("Fogo", 22, 95)
        ))
    )
}