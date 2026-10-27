
package com.ifpr.androidapptemplatear.ui

import androidx.fragment.app.Fragment
import com.ifpr.androidapptemplatear.R
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.media.MediaPlayer
import android.util.Log
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.ar.core.Config
import io.github.sceneview.ar.ArSceneView
import io.github.sceneview.ar.node.ArModelNode
import io.github.sceneview.ar.node.PlacementMode
import io.github.sceneview.math.Position
import okio.IOException
import java.io.File
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import io.github.sceneview.math.Rotation
import kotlin.random.Random

class CameraFragment : Fragment() {

    private lateinit var btnBattle: ExtendedFloatingActionButton
    private lateinit var selectionOverlay: View
    private lateinit var battleHud: View
    private lateinit var enemyPanel: View
    private lateinit var movesGrid: GridLayout
    private lateinit var battleLog: TextView
    private lateinit var playerHpBar: ProgressBar
    private lateinit var enemyHpBar: ProgressBar
    private lateinit var playerNameTv: TextView
    private lateinit var enemyNameTv: TextView

    private var inBattle = false
    private var battleStarted = false   // true depois de fixar o dragão e criar o inimigo
    private var playerDragon: Dragon? = null
    private var enemyDragon: Dragon? = null
    private var playerHp = 0
    private var enemyHp = 0
    private var enemyNode: ArModelNode? = null
    private var turnLocked = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sceneView: ArSceneView
    private lateinit var modelNode: ArModelNode
    private lateinit var placeButton: ExtendedFloatingActionButton
    private lateinit var sharedViewModel: SharedModelViewModel

    private val CAMERA_PERMISSION_CODE = 1001
    private var currentModelFile = "neutrofilo.glb"
    private var mediaPlayer: MediaPlayer? = null

    private var _binding: com.ifpr.androidapptemplatear.databinding.FragmentCameraBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_camera, container, false)

        sceneView = view.findViewById<ArSceneView>(R.id.sceneView).apply {
            lightEstimationMode = Config.LightEstimationMode.DISABLED
        }

        placeButton = view.findViewById(R.id.place)
        placeButton.setOnClickListener {
            placeModel()
        }

        sharedViewModel =
            ViewModelProvider(requireActivity())[SharedModelViewModel::class.java]

        sharedViewModel.selectedModel.observe(viewLifecycleOwner) { path ->
            currentModelFile = path

            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    android.Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                loadModel(path)
            }
        }
        setupBattleUi(view)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        checkCameraPermission()
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        releaseAudio()
        _binding = null
        super.onDestroyView()
    }


    private fun placeModel() {
        if (!::modelNode.isInitialized) return

        modelNode.anchor()
        modelNode.isEditable = true
        modelNode.isScaleEditable = false
        sceneView.planeRenderer.isVisible = false

        // Reproduz o som correspondente ao modelo posicionado
        playModelSound(currentModelFile)
        if (inBattle && !battleStarted) startBattle()
    }


    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(android.Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        } else {
            initAR()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                initAR()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Permissão de câmera negada",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private var arInitialized = false

    private fun initAR() {
        if (arInitialized) return
        arInitialized = true
        loadModel(currentModelFile)
    }

    private fun loadModel(fileName: String) {
        currentModelFile = fileName

        if (::modelNode.isInitialized) {
            sceneView.removeChild(modelNode)
        }

        modelNode = ArModelNode(
            sceneView.engine,
            PlacementMode.INSTANT
        ).apply {
            loadModelGlbAsync(
                glbFileLocation = getFilePath(
                    requireContext(),
                    fileName
                ),
                centerOrigin = Position(0f, 0f, 1f)
            ) {
                if (!isAdded) return@loadModelGlbAsync

                sceneView.planeRenderer.isVisible = true
                Log.d("DRAGON_AUDIO", "Modelo carregado: $fileName")

                // Reproduz o som ao terminar o carregamento do modelo.
                playModelSound(fileName)
            }

            isEditable = true
            isScaleEditable = false
        }

        sceneView.addChild(modelNode)
    }

    private fun playModelSound(fileName: String) {
        releaseAudio()

        val modelName = fileName
            .substringAfterLast("/")
            .substringBefore("?")
            .substringAfterLast("%2F")
            .lowercase()

        val soundRes = when {
            modelName.contains("mountain_dragon") ->
                R.raw.mountain_dragon_roar

            modelName.contains("dragon_ver2") ->
                R.raw.dragon_roar

            else -> null
        }

        if (soundRes == null) {
            Log.d("DRAGON_AUDIO", "Sem áudio associado ao modelo: $modelName")
            return
        }

        try {
            val player = MediaPlayer.create(requireContext(), soundRes)

            if (player == null) {
                Log.e("DRAGON_AUDIO", "Não foi possível abrir o arquivo de áudio")
                return
            }

            mediaPlayer = player

            player.setOnCompletionListener {
                it.release()
                if (mediaPlayer === it) {
                    mediaPlayer = null
                }
            }

            player.setOnErrorListener { mp, what, extra ->
                Log.e(
                    "DRAGON_AUDIO",
                    "Erro MediaPlayer: what=$what, extra=$extra"
                )
                mp.release()
                if (mediaPlayer === mp) {
                    mediaPlayer = null
                }
                true
            }

            player.start()
            Log.d("DRAGON_AUDIO", "Som iniciado para $modelName")

        } catch (e: Exception) {
            Log.e("DRAGON_AUDIO", "Erro ao reproduzir o som", e)
        }
    }

    private fun releaseAudio() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (e: Exception) {
                Log.e("DRAGON_AUDIO", "Erro ao interromper áudio", e)
            } finally {
                player.release()
            }
        }
        mediaPlayer = null
    }

    private fun getFilePath(context: Context?, fileName: String): String {
        try {
            context?.assets?.open(fileName)?.use { }
            return fileName
        } catch (e: IOException) {
            val internalFile = File(context?.filesDir, fileName)

            return if (internalFile.exists()) {
                Uri.fromFile(internalFile).toString()
            } else {
                Log.e("DRAGON_AUDIO", "Modelo não encontrado: $fileName")
                fileName
            }
        }
    }
    private fun setupBattleUi(v: View) {
        btnBattle = v.findViewById(R.id.btnBattle)
        selectionOverlay = v.findViewById(R.id.selectionOverlay)
        battleHud = v.findViewById(R.id.battleHud)
        enemyPanel = v.findViewById(R.id.enemyPanel)
        movesGrid = v.findViewById(R.id.movesGrid)
        battleLog = v.findViewById(R.id.battleLog)
        playerHpBar = v.findViewById(R.id.playerHpBar)
        enemyHpBar = v.findViewById(R.id.enemyHpBar)
        playerNameTv = v.findViewById(R.id.playerName)
        enemyNameTv = v.findViewById(R.id.enemyName)

        btnBattle.setOnClickListener {
            selectionOverlay.visibility = View.VISIBLE
            btnBattle.visibility = View.GONE
            placeButton.visibility = View.GONE
        }

        val buttons = listOf(R.id.btnDragon1, R.id.btnDragon2, R.id.btnDragon3)
        Dragons.all.forEachIndexed { i, dragon ->
            v.findViewById<Button>(buttons[i]).apply {
                text = "${dragon.name}  (HP ${dragon.maxHp})"
                setOnClickListener { chooseDragon(dragon) }
            }
        }

        v.findViewById<View>(R.id.btnCancelSelect).setOnClickListener { exitBattle() }
        v.findViewById<View>(R.id.btnExitBattle).setOnClickListener { exitBattle() }
    }

    private fun chooseDragon(dragon: Dragon) {
        inBattle = true
        battleStarted = false
        playerDragon = dragon
        enemyDragon = Dragons.all.filter { it != dragon }.random()

        selectionOverlay.visibility = View.GONE
        placeButton.visibility = View.VISIBLE
        Toast.makeText(requireContext(),
            "Aponte para o chão e toque em Place", Toast.LENGTH_LONG).show()

        loadModel(dragon.glb)   // reaproveita o seu loadModel
    }

    private fun startBattle() {
        val player = playerDragon ?: return
        val enemy = enemyDragon ?: return
        battleStarted = true

        // Inimigo ao lado do jogador, no mundo (não ancorado, para não brigar com a âncora do jogador)
        enemyNode = ArModelNode(sceneView.engine, PlacementMode.DISABLED).apply {
            loadModelGlbAsync(
                glbFileLocation = getFilePath(requireContext(), enemy.glb),
                centerOrigin = Position(0f, 0f, 1f)
            )
            worldPosition = modelNode.worldPosition + Position(x = 1.2f)
            rotation = Rotation(y = 180f)   // vira de frente para o jogador (ajuste se precisar)
        }
        sceneView.addChild(enemyNode!!)

        playerHp = player.maxHp
        enemyHp = enemy.maxHp
        playerHpBar.max = player.maxHp; playerHpBar.progress = playerHp
        enemyHpBar.max = enemy.maxHp; enemyHpBar.progress = enemyHp
        playerNameTv.text = player.name
        enemyNameTv.text = enemy.name

        movesGrid.removeAllViews()
        player.moves.forEach { move ->
            movesGrid.addView(Button(requireContext()).apply {
                text = "${move.name} (${move.power})"
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
                setOnClickListener { playerAttack(move) }
            })
        }

        placeButton.visibility = View.GONE
        battleHud.visibility = View.VISIBLE
        enemyPanel.visibility = View.VISIBLE
        setMovesEnabled(true)
        battleLog.text = "Um ${enemy.name} selvagem apareceu!"
    }

    private fun playerAttack(move: Move) {
        if (turnLocked) return
        turnLocked = true
        setMovesEnabled(false)
        val player = playerDragon ?: return

        battleLog.text = "${player.name} usou ${move.name}!"
        lunge(modelNode, +1f)
        handler.postDelayed({
            val dmg = rollDamage(move)
            enemyHp = (enemyHp - dmg).coerceAtLeast(0)
            enemyHpBar.progress = enemyHp
            battleLog.text = if (dmg == 0) "${player.name} errou!" else "Causou $dmg de dano!"

            if (enemyHp == 0) endBattle(won = true)
            else handler.postDelayed({ enemyAttack() }, 1100)
        }, 300)
    }

    private fun enemyAttack() {
        val enemy = enemyDragon ?: return
        val node = enemyNode ?: return
        val move = enemy.moves.random()

        battleLog.text = "${enemy.name} usou ${move.name}!"
        lunge(node, +1f)
        handler.postDelayed({
            val dmg = rollDamage(move)
            playerHp = (playerHp - dmg).coerceAtLeast(0)
            playerHpBar.progress = playerHp
            battleLog.text = if (dmg == 0) "${enemy.name} errou!" else "Você levou $dmg de dano!"

            if (playerHp == 0) endBattle(won = false)
            else { turnLocked = false; setMovesEnabled(true) }
        }, 300)
    }

    private fun rollDamage(move: Move): Int =
        if (Random.nextInt(100) < move.accuracy) (move.power + Random.nextInt(-3, 4)).coerceAtLeast(1) else 0

    // Avança o modelo em direção ao oponente e volta (mexe só no modelo, não na âncora)
    private fun lunge(node: ArModelNode, direction: Float) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            repeatCount = 1
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                val t = it.animatedValue as Float
                node.modelPosition = node.modelPosition.copy(x = direction * 0.5f * t)
            }
            start()
        }
        playerDragon?.let { if (node === modelNode) playModelSound(it.glb) }
        enemyDragon?.let { if (node === enemyNode) playModelSound(it.glb) }
    }

    private fun setMovesEnabled(enabled: Boolean) {
        for (i in 0 until movesGrid.childCount) movesGrid.getChildAt(i).isEnabled = enabled
    }

    private fun endBattle(won: Boolean) {
        battleLog.text = if (won) "Você venceu! 🐉" else "Seu dragão foi derrotado..."
        setMovesEnabled(false)
    }

    private fun exitBattle() {
        handler.removeCallbacksAndMessages(null)
        enemyNode?.let { sceneView.removeChild(it) }
        enemyNode = null
        inBattle = false; battleStarted = false; turnLocked = false

        selectionOverlay.visibility = View.GONE
        battleHud.visibility = View.GONE
        enemyPanel.visibility = View.GONE
        btnBattle.visibility = View.VISIBLE
        placeButton.visibility = View.VISIBLE
    }
}
