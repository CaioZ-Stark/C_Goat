package com.ifpr.androidapptemplatear.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.ifpr.androidapptemplatear.R
import io.github.sceneview.SceneView
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random


class CameraFragment : Fragment() {

    private enum class Mode {
        CHECKING,
        AR,
        CAMERA,
        STATIC
    }

    companion object {
        private const val AVAILABILITY_TIMEOUT_MS = 4000L
        private const val AR_TIMEOUT_MS = 15000L
        private const val CAMERA_TIMEOUT_MS = 8000L
        private const val AUDIO_TAG = "DRAGON_AUDIO"

        // Pontuação
        private const val POINTS_PER_WIN = 100
        private const val PREFS_NAME = "dragon_battle"
        private const val KEY_SCORE = "score"
        private const val KEY_BEST = "best_score"
    }

    // ---------------------------------------------------------------
    // Localização (professor)
    // Se a classe LocalizacaoController ainda não existir no seu
    // projeto, apague esta linha e o bloco marcado em onViewCreated.
    // ---------------------------------------------------------------
    private val localizacaoController = LocalizacaoController(this)

    // ---------------------------------------------------------------
    // Estado de cena / modo (base do professor)
    // ---------------------------------------------------------------
    private var mode = Mode.CHECKING

    private var container: FrameLayout? = null
    private var placeButton: ExtendedFloatingActionButton? = null
    private var statusText: TextView? = null
    private var overlay: View? = null

    private var sceneView: SceneView? = null
    private var arView: ARSceneView? = null

    private var modelNode: ModelNode? = null
    private var anchorNode: AnchorNode? = null
    private var placementHit: HitResult? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraPreview: Preview? = null

    private var loadJob: Job? = null
    private var availabilityJob: Job? = null
    private var arWatchdogJob: Job? = null
    private var cameraWatchdogJob: Job? = null

    private var generation = 0
    private var modelGeneration = 0
    private var pendingAr = false

    private var arTracking = false
    private var cameraStreaming = false

    private var currentModelFile = "neutrofilo.glb"

    private lateinit var sharedViewModel: SharedModelViewModel

    // ---------------------------------------------------------------
    // Estado de áudio
    // ---------------------------------------------------------------
    private var mediaPlayer: MediaPlayer? = null

    // ---------------------------------------------------------------
    // Estado de batalha
    // ---------------------------------------------------------------
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

    private lateinit var gameOverOverlay: View
    private lateinit var gameOverTitle: TextView
    private lateinit var gameOverMsg: TextView
    private lateinit var btnRematch: Button
    private lateinit var scoreText: TextView
    private lateinit var gameOverScore: TextView
    private var score = 0
    private var bestScore = 0

    private var inBattle = false
    private var battleStarted = false   // true depois de fixar o dragão e criar o inimigo
    private var playerDragon: Dragon? = null
    private var enemyDragon: Dragon? = null
    private var playerHp = 0
    private var enemyHp = 0
    private var enemyNode: ModelNode? = null
    private var enemyLoadJob: Job? = null
    private var turnLocked = false
    private val handler = Handler(Looper.getMainLooper())

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (container != null) {
                when {
                    !granted -> startPreview(useCamera = false)
                    pendingAr -> startAr()
                    else -> startPreview(useCamera = true)
                }
            }
        }

    // ---------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------
    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(
            R.layout.fragment_camera,
            parent,
            false
        )
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        container = view.findViewById(R.id.sceneContainer)
        placeButton = view.findViewById(R.id.place)

        createControls()
        setupBattleUi(view)

        sharedViewModel =
            ViewModelProvider(requireActivity())[
                SharedModelViewModel::class.java
            ]

        val selectedModel = sharedViewModel.selectedModel.value

        if (!selectedModel.isNullOrBlank()) {
            currentModelFile = selectedModel
        } else {
            sharedViewModel.selectModel(currentModelFile)
        }

        sharedViewModel.selectedModel.observe(viewLifecycleOwner) { path ->
            if (!path.isNullOrBlank() && path != currentModelFile) {
                currentModelFile = path
                loadModel()
            }
        }

        placeButton?.setOnClickListener {
            when (mode) {
                Mode.AR -> placeModel()

                Mode.CAMERA,
                Mode.STATIC -> {
                    if (inBattle && !battleStarted) {
                        // Sem AR: o "Centralizar" vira "Iniciar batalha"
                        startBattle()
                    } else {
                        startPreview(useCamera = mode == Mode.CAMERA)
                    }
                }

                Mode.CHECKING -> Unit
            }
        }

        checkAvailability()

        // --- Localização (professor) -------------------------------
        val rootParent = container?.parent as? ViewGroup
        if (rootParent != null) {
            localizacaoController.conectar(rootParent) { arquivo ->
                // durante a batalha, não troca o dragão do jogador
                if (!inBattle) sharedViewModel.selectModel(arquivo)
            }
            placeButton?.bringToFront()
        }
        // -----------------------------------------------------------

        // A UI de batalha precisa ficar por cima de tudo que foi adicionado
        btnBattle.bringToFront()
        battleHud.bringToFront()
        enemyPanel.bringToFront()
        selectionOverlay.bringToFront()
        gameOverOverlay.bringToFront()
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        releaseAudio()

        availabilityJob?.cancel()
        availabilityJob = null

        clearScene()

        overlay?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }

        overlay = null
        statusText = null
        placeButton = null
        container = null

        pendingAr = false
        inBattle = false
        battleStarted = false
        turnLocked = false
        mode = Mode.CHECKING

        super.onDestroyView()
    }

    // ---------------------------------------------------------------
    // Controles de status (professor)
    // ---------------------------------------------------------------
    private fun createControls() {
        val parent = container?.parent as? ViewGroup ?: return

        val layer = FrameLayout(requireContext())

        val panel = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.argb(190, 25, 30, 35))
        }

        statusText = TextView(requireContext()).apply {
            text = "Preparando visualização…"
            textSize = 13f
            setTextColor(Color.WHITE)
        }

        panel.addView(statusText)

        layer.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
                bottomMargin = dp(85)
            }
        )

        parent.addView(
            layer,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        overlay = layer

        parent.findViewById<View>(R.id.titleImage)?.bringToFront()
        placeButton?.bringToFront()
    }

    /**
     * Identificação por características comuns de emuladores.
     * Por escolha deste aplicativo, eles iniciam sem ARCore.
     */
    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.MODEL.contains("google_sdk", ignoreCase = true) ||
                Build.MODEL.contains("Emulator", ignoreCase = true) ||
                Build.MODEL.contains(
                    "Android SDK built for",
                    ignoreCase = true
                ) ||
                Build.PRODUCT.contains("sdk", ignoreCase = true) ||
                Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
                Build.HARDWARE.contains("ranchu", ignoreCase = true)
    }

    // ---------------------------------------------------------------
    // Disponibilidade / permissão / modos (professor)
    // ---------------------------------------------------------------
    private fun checkAvailability() {
        availabilityJob?.cancel()

        // Não tenta iniciar ARCore no emulador.
        if (isEmulator()) {
            requestMode(useAr = false)
            return
        }

        val expected = generation
        var resolved = false

        availabilityJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(AVAILABILITY_TIMEOUT_MS)

            if (
                !resolved &&
                container != null &&
                expected == generation
            ) {
                resolved = true
                requestMode(useAr = false)
            }
        }

        try {
            ArCoreApk.getInstance()
                .checkAvailabilityAsync(requireContext()) { availability ->
                    activity?.runOnUiThread {
                        if (
                            !resolved &&
                            container != null &&
                            expected == generation
                        ) {
                            resolved = true
                            availabilityJob?.cancel()
                            availabilityJob = null

                            val installed =
                                availability ==
                                        ArCoreApk.Availability.SUPPORTED_INSTALLED

                            requestMode(useAr = installed)
                        }
                    }
                }
        } catch (_: Exception) {
            if (
                !resolved &&
                container != null &&
                expected == generation
            ) {
                resolved = true
                availabilityJob?.cancel()
                availabilityJob = null

                requestMode(useAr = false)
            }
        }
    }

    private fun requestMode(useAr: Boolean) {
        pendingAr = useAr

        val permitted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        when {
            !permitted -> {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }

            useAr -> startAr()

            else -> startPreview(useCamera = true)
        }
    }

    private fun startAr() {
        val host = container ?: return

        clearScene()
        mode = Mode.AR

        val expected = generation

        statusText?.text = "Aponte para uma mesa ou para o chão."

        placeButton?.apply {
            text = "Posicionar"
            isEnabled = false
        }

        try {
            val ar = ARSceneView(requireContext())

            arView = ar
            sceneView = ar

            ar.configureSession { _, config ->
                config.planeFindingMode =
                    Config.PlaneFindingMode.HORIZONTAL

                config.lightEstimationMode =
                    Config.LightEstimationMode.DISABLED
            }

            ar.onSessionFailed = {
                host.post {
                    if (
                        container != null &&
                        expected == generation &&
                        mode == Mode.AR
                    ) {
                        startPreview(useCamera = true)
                    }
                }
            }

            ar.onSessionUpdated = { _, frame ->
                if (
                    expected == generation &&
                    mode == Mode.AR
                ) {
                    arTracking =
                        frame.camera.trackingState ==
                                TrackingState.TRACKING

                    if (anchorNode == null) {
                        placementHit =
                            if (
                                arTracking &&
                                ar.width > 0 &&
                                ar.height > 0
                            ) {
                                frame.hitTest(
                                    ar.width / 2f,
                                    ar.height / 2f
                                ).firstOrNull { hit ->
                                    val plane = hit.trackable as? Plane

                                    plane != null &&
                                            plane.type ==
                                            Plane.Type.HORIZONTAL_UPWARD_FACING &&
                                            plane.isPoseInPolygon(hit.hitPose)
                                }
                            } else {
                                null
                            }

                        val ready =
                            placementHit != null &&
                                    modelNode != null

                        placeButton?.isEnabled = ready

                        statusText?.text =
                            if (ready) {
                                "Superfície encontrada. Toque em Posicionar."
                            } else {
                                "Mova o aparelho devagar sobre uma superfície."
                            }
                    }
                }
            }

            ar.lifecycle = viewLifecycleOwner.lifecycle

            host.addView(ar, fullSize())

            startArWatchdog(expected)
            loadModel()

        } catch (_: Exception) {
            startPreview(useCamera = true)
        }
    }

    /**
     * Troca automaticamente quando o ARCore não consegue rastrear.
     * Conta somente o tempo em que a tela está em primeiro plano.
     * A ausência de uma superfície, sozinha, não provoca a troca.
     */
    private fun startArWatchdog(expected: Int) {
        arWatchdogJob?.cancel()

        arWatchdogJob = viewLifecycleOwner.lifecycleScope.launch {
            var withoutTrackingMs = 0L
            var lastTime = SystemClock.elapsedRealtime()

            while (isActive) {
                delay(500L)

                if (
                    container == null ||
                    expected != generation ||
                    mode != Mode.AR
                ) {
                    return@launch
                }

                val now = SystemClock.elapsedRealtime()
                val elapsed = now - lastTime
                lastTime = now

                val resumed = viewLifecycleOwner.lifecycle
                    .currentState.isAtLeast(Lifecycle.State.RESUMED)

                if (!resumed || arTracking) {
                    withoutTrackingMs = 0L
                } else {
                    withoutTrackingMs += elapsed
                }

                if (withoutTrackingMs >= AR_TIMEOUT_MS) {
                    startPreview(useCamera = true)
                    return@launch
                }
            }
        }
    }

    private fun startPreview(useCamera: Boolean) {
        val host = container ?: return

        clearScene()

        mode = if (useCamera) Mode.CAMERA else Mode.STATIC

        placeButton?.apply {
            text = "Centralizar"
            isEnabled = false
        }

        statusText?.text = if (useCamera) {
            "Modelo sobre a câmera, sem fixação no ambiente."
        } else {
            "Visualização 3D sem câmera."
        }

        host.setBackgroundColor(Color.rgb(45, 55, 65))

        val cameraView = if (useCamera) {
            PreviewView(requireContext()).apply {
                implementationMode =
                    PreviewView.ImplementationMode.COMPATIBLE

                scaleType = PreviewView.ScaleType.FILL_CENTER

                host.addView(this, fullSize())
            }
        } else {
            null
        }

        try {
            val scene = SceneView(
                context = requireContext(),
                isOpaque = !useCamera
            )

            scene.lifecycle = viewLifecycleOwner.lifecycle
            scene.cameraNode.position = Position(0f, 0f, 5f)
            scene.cameraNode.lookAt(Position(0f, 0f, 0f))

            sceneView = scene

            host.addView(scene, fullSize())

            loadModel()

            if (cameraView != null) {
                openCamera(cameraView)
            }
        } catch (_: Exception) {
            statusText?.text = "Não foi possível iniciar a cena 3D."
        }
    }

    private fun openCamera(previewView: PreviewView) {
        val context = context ?: return

        val expected = generation
        val owner = viewLifecycleOwner

        // O prazo também cobre a obtenção do CameraProvider.
        startCameraWatchdog(expected)

        try {
            val future = ProcessCameraProvider.getInstance(context)

            future.addListener({
                if (
                    container == null ||
                    expected != generation ||
                    mode != Mode.CAMERA
                ) {
                    return@addListener
                }

                try {
                    val provider = future.get()

                    val selector = when {
                        provider.hasCamera(
                            CameraSelector.DEFAULT_BACK_CAMERA
                        ) -> CameraSelector.DEFAULT_BACK_CAMERA

                        provider.hasCamera(
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        ) -> CameraSelector.DEFAULT_FRONT_CAMERA

                        else -> {
                            startPreview(useCamera = false)
                            return@addListener
                        }
                    }

                    val preview = Preview.Builder().build()

                    preview.setSurfaceProvider(
                        previewView.surfaceProvider
                    )

                    cameraProvider = provider
                    cameraPreview = preview

                    previewView.previewStreamState.observe(owner) { state ->
                        if (
                            expected == generation &&
                            mode == Mode.CAMERA
                        ) {
                            cameraStreaming =
                                state == PreviewView.StreamState.STREAMING
                        }
                    }

                    provider.bindToLifecycle(
                        owner,
                        selector,
                        preview
                    )

                } catch (_: Exception) {
                    if (
                        expected == generation &&
                        container != null
                    ) {
                        startPreview(useCamera = false)
                    }
                }
            }, ContextCompat.getMainExecutor(context))

        } catch (_: Exception) {
            if (
                expected == generation &&
                container != null
            ) {
                startPreview(useCamera = false)
            }
        }
    }

    private fun startCameraWatchdog(expected: Int) {
        cameraWatchdogJob?.cancel()

        cameraWatchdogJob = viewLifecycleOwner.lifecycleScope.launch {
            var withoutCameraMs = 0L
            var lastTime = SystemClock.elapsedRealtime()

            while (isActive) {
                delay(500L)

                if (
                    container == null ||
                    expected != generation ||
                    mode != Mode.CAMERA
                ) {
                    return@launch
                }

                val now = SystemClock.elapsedRealtime()
                val elapsed = now - lastTime
                lastTime = now

                val resumed = viewLifecycleOwner.lifecycle
                    .currentState.isAtLeast(Lifecycle.State.RESUMED)

                if (!resumed || cameraStreaming) {
                    withoutCameraMs = 0L
                } else {
                    withoutCameraMs += elapsed
                }

                if (withoutCameraMs >= CAMERA_TIMEOUT_MS) {
                    startPreview(useCamera = false)
                    return@launch
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Modelo 3D do jogador
    // ---------------------------------------------------------------
    private fun loadModel() {
        val scene = sceneView ?: return
        val location = modelLocation(currentModelFile)

        if (location == null) {
            statusText?.text =
                "Modelo não encontrado: $currentModelFile"

            placeButton?.isEnabled = false
            return
        }

        loadJob?.cancel()
        removeModel()

        val expected = generation
        val expectedModel = ++modelGeneration
        val isAr = mode == Mode.AR
        val fileName = currentModelFile

        placeButton?.isEnabled = false

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val instance =
                    scene.modelLoader.loadModelInstance(location)

                if (
                    expected != generation ||
                    expectedModel != modelGeneration ||
                    sceneView !== scene
                ) {
                    return@launch
                }

                if (instance == null) {
                    statusText?.text = "Não foi possível abrir o modelo."
                    return@launch
                }

                val node = ModelNode(
                    modelInstance = instance,
                    scaleToUnits = modelScale(),
                    centerOrigin = Position(0f, 0f, 0f)
                )

                node.isEditable = true
                modelNode = node

                Log.d(AUDIO_TAG, "Modelo carregado: $fileName")
                // Som ao terminar o carregamento
                playModelSound(fileName)

                if (!isAr) {
                    scene.addChildNode(node)
                    placeButton?.isEnabled = true

                    if (inBattle && !battleStarted) {
                        placeButton?.text = "Iniciar batalha"
                        statusText?.text = "Toque em Iniciar batalha."
                    }
                }

            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (
                    expected == generation &&
                    expectedModel == modelGeneration
                ) {
                    statusText?.text =
                        "Erro ao carregar: $fileName"
                }
            }
        }
    }

    private fun placeModel() {
        val ar = arView ?: return

        if (anchorNode != null) {
            loadModel()
            return
        }

        val hit = placementHit ?: return
        val model = modelNode ?: return

        try {
            val anchor = AnchorNode(
                engine = ar.engine,
                anchor = hit.createAnchor()
            )

            anchor.addChildNode(model)
            ar.addChildNode(anchor)

            anchorNode = anchor
            placementHit = null

            placeButton?.apply {
                text = "Reposicionar"
                isEnabled = true
            }

            statusText?.text = "Objeto posicionado no ambiente."

            // Som do modelo posicionado
            playModelSound(currentModelFile)

            if (inBattle && !battleStarted) startBattle()

        } catch (_: Exception) {
            statusText?.text =
                "Não foi possível posicionar. Tente outra superfície."
        }
    }

    private fun modelScale(): Float = when {
        mode == Mode.AR -> 0.5f
        inBattle -> 0.7f
        else -> 1.0f
    }

    private fun modelLocation(fileName: String): String? {
        val context = context ?: return null

        try {
            context.assets.open(fileName).use { }
            return fileName
        } catch (_: Exception) {
            // Procura no armazenamento interno.
        }

        val file = File(context.filesDir, fileName)

        return if (file.isFile) {
            Uri.fromFile(file).toString()
        } else {
            null
        }
    }

    private fun removeEnemy() {
        enemyLoadJob?.cancel()
        enemyLoadJob = null

        enemyNode?.let { enemy ->
            runCatching {
                anchorNode?.removeChildNode(enemy)
                sceneView?.removeChildNode(enemy)
                enemy.destroy()
            }
        }

        enemyNode = null
    }

    private fun removeModel() {
        val scene = sceneView

        removeEnemy()

        modelNode?.let { model ->
            runCatching {
                anchorNode?.removeChildNode(model)
                scene?.removeChildNode(model)
                model.destroy()
            }
        }

        modelNode = null

        anchorNode?.let { anchor ->
            runCatching {
                scene?.removeChildNode(anchor)
                anchor.anchor.detach()
                anchor.destroy()
            }
        }

        anchorNode = null
        placementHit = null

        if (mode == Mode.AR) {
            placeButton?.text = "Posicionar"
        }
    }

    private fun clearScene() {
        generation++
        modelGeneration++

        arWatchdogJob?.cancel()
        arWatchdogJob = null

        cameraWatchdogJob?.cancel()
        cameraWatchdogJob = null

        loadJob?.cancel()
        loadJob = null

        cameraPreview?.let { preview ->
            runCatching {
                cameraProvider?.unbind(preview)
            }
        }

        cameraPreview = null
        cameraProvider = null

        arTracking = false
        cameraStreaming = false

        // Se o modo trocou no meio da batalha (ex.: AR -> câmera),
        // a cena é destruída, então a batalha é encerrada.
        if (inBattle) {
            exitBattle()
        }

        removeModel()

        sceneView?.let { scene ->
            runCatching {
                scene.lifecycle = null
                scene.destroy()
            }
        }

        sceneView = null
        arView = null

        container?.removeAllViews()
    }

    private fun fullSize() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    // ---------------------------------------------------------------
    // Áudio
    // ---------------------------------------------------------------
    private fun playModelSound(fileName: String) {
        val ctx = context ?: return

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

            // Se tiver o áudio do Dark Dragon em res/raw, descomente:
            // modelName.contains("dark_dragon") ->
            //     R.raw.dark_dragon_roar

            else -> null
        }

        if (soundRes == null) {
            Log.d(AUDIO_TAG, "Sem áudio associado ao modelo: $modelName")
            return
        }

        try {
            val player = MediaPlayer.create(ctx, soundRes)

            if (player == null) {
                Log.e(AUDIO_TAG, "Não foi possível abrir o arquivo de áudio")
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
                Log.e(AUDIO_TAG, "Erro MediaPlayer: what=$what, extra=$extra")
                mp.release()
                if (mediaPlayer === mp) {
                    mediaPlayer = null
                }
                true
            }

            player.start()
            Log.d(AUDIO_TAG, "Som iniciado para $modelName")

        } catch (e: Exception) {
            Log.e(AUDIO_TAG, "Erro ao reproduzir o som", e)
        }
    }

    private fun releaseAudio() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (e: Exception) {
                Log.e(AUDIO_TAG, "Erro ao interromper áudio", e)
            } finally {
                player.release()
            }
        }
        mediaPlayer = null
    }

    // ---------------------------------------------------------------
    // Batalha
    // ---------------------------------------------------------------
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

        gameOverOverlay = v.findViewById(R.id.gameOverOverlay)
        gameOverTitle = v.findViewById(R.id.gameOverTitle)
        gameOverMsg = v.findViewById(R.id.gameOverMsg)
        btnRematch = v.findViewById(R.id.btnRematch)
        scoreText = v.findViewById(R.id.scoreText)
        gameOverScore = v.findViewById(R.id.gameOverScore)
        loadScore()

        btnBattle.setOnClickListener {
            selectionOverlay.visibility = View.VISIBLE
            btnBattle.visibility = View.GONE
            placeButton?.visibility = View.GONE
        }

        val buttons = listOf(R.id.btnDragon1, R.id.btnDragon2, R.id.btnDragon3)
        Dragons.all.forEachIndexed { i, dragon ->
            v.findViewById<Button>(buttons[i]).apply {
                text = "${dragon.name}  (HP ${dragon.maxHp})"
                setOnClickListener { chooseDragon(dragon) }
            }
        }

        v.findViewById<View>(R.id.btnCancelSelect).setOnClickListener { exitBattle() }
        v.findViewById<View>(R.id.btnExitBattle).setOnClickListener { leaveBattle() }
        btnRematch.setOnClickListener { restartBattle() }
        v.findViewById<View>(R.id.btnQuit).setOnClickListener { leaveBattle() }
    }

    private fun chooseDragon(dragon: Dragon) {
        inBattle = true
        battleStarted = false
        turnLocked = false
        playerDragon = dragon
        enemyDragon = Dragons.all.filter { it != dragon }.random()

        // Define o arquivo direto (sem passar pelo ViewModel) e recarrega
        currentModelFile = dragon.glb

        selectionOverlay.visibility = View.GONE
        placeButton?.visibility = View.VISIBLE

        val hint = if (mode == Mode.AR) {
            "Aponte para o chão e toque em Posicionar"
        } else {
            "Toque em Iniciar batalha"
        }
        Toast.makeText(requireContext(), hint, Toast.LENGTH_LONG).show()

        statusText?.text = "Carregando ${dragon.name}…"
        loadModel()
        preloadEnemy()   // sempre depois do loadModel()
    }

    private fun startBattle() {
        val player = playerDragon ?: return
        val enemy = enemyDragon ?: return
        val scene = sceneView ?: return
        val playerModel = modelNode ?: return
        if (battleStarted) return

        val location = modelLocation(enemy.glb)
        if (location == null) {
            Toast.makeText(
                requireContext(),
                "Modelo do inimigo não encontrado: ${enemy.glb}",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        battleStarted = true
        turnLocked = false

        val isAr = mode == Mode.AR
        val expected = generation

        // AR: jogador fica na âncora e o inimigo ao lado
        // Sem AR: jogador à esquerda e inimigo à direita
        val playerX = if (isAr) 0f else -0.55f
        val enemyX = if (isAr) 0.6f else 0.55f

        playerModel.position = Position(x = playerX)

        val pending = enemyLoadJob   // pré-carregamento em andamento (não cancelar)
        enemyLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                pending?.join()
                val node = enemyNode ?: buildEnemyNode(scene, enemy, enemyX)
                if (node == null || expected != generation || sceneView !== scene) {
                    return@launch
                }
                enemyNode = node

                val anchor = anchorNode
                if (isAr && anchor != null) anchor.addChildNode(node)
                else scene.addChildNode(node)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(AUDIO_TAG, "Erro ao carregar inimigo", e)
            }
        }

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

        placeButton?.visibility = View.GONE
        overlay?.visibility = View.GONE   // esconde o painel de status durante a luta
        battleHud.visibility = View.VISIBLE
        enemyPanel.visibility = View.VISIBLE
        setMovesEnabled(true)
        battleLog.text = "Um ${enemy.name} selvagem apareceu!"
    }

    private fun playerAttack(move: Move) {
        if (turnLocked) return
        val player = playerDragon ?: return
        val node = modelNode ?: return

        turnLocked = true
        setMovesEnabled(false)

        battleLog.text = "${player.name} usou ${move.name}!"
        lunge(node, +1f)
        handler.postDelayed({
            val dmg = rollDamage(move)
            enemyHp = (enemyHp - dmg).coerceAtLeast(0)
            enemyHpBar.progress = enemyHp
            battleLog.text =
                if (dmg == 0) "${player.name} errou!" else "Causou $dmg de dano!"

            if (enemyHp == 0) endBattle(won = true)
            else handler.postDelayed({ enemyAttack() }, 1100)
        }, 300)
    }

    private fun enemyAttack() {
        val enemy = enemyDragon ?: return
        val node = enemyNode
        if (node == null) {
            // inimigo não carregou: devolve o turno para o jogador
            turnLocked = false
            setMovesEnabled(true)
            return
        }
        val move = enemy.moves.random()

        battleLog.text = "${enemy.name} usou ${move.name}!"
        lunge(node, -1f)   // o inimigo está à direita, então avança para a esquerda
        handler.postDelayed({
            val dmg = rollDamage(move)
            playerHp = (playerHp - dmg).coerceAtLeast(0)
            playerHpBar.progress = playerHp
            battleLog.text =
                if (dmg == 0) "${enemy.name} errou!" else "Você levou $dmg de dano!"

            if (playerHp == 0) endBattle(won = false)
            else { turnLocked = false; setMovesEnabled(true) }
        }, 300)
    }

    private fun rollDamage(move: Move): Int =
        if (Random.nextInt(100) < move.accuracy)
            (move.power + Random.nextInt(-3, 4)).coerceAtLeast(1)
        else 0

    // Avança o modelo em direção ao oponente e volta
    private fun lunge(node: ModelNode, direction: Float) {
        val base = node.position
        val reach = if (mode == Mode.AR) 0.25f else 0.3f

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            repeatCount = 1
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                val t = it.animatedValue as Float
                runCatching {
                    node.position = base.copy(x = base.x + direction * reach * t)
                }
            }
            start()
        }

        val dragon = if (node === modelNode) playerDragon else enemyDragon
        dragon?.let { playModelSound(it.glb) }
    }

    private fun setMovesEnabled(enabled: Boolean) {
        for (i in 0 until movesGrid.childCount) {
            movesGrid.getChildAt(i).isEnabled = enabled
        }
    }

    private fun prefs() =
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadScore() {
        score = prefs().getInt(KEY_SCORE, 0)
        bestScore = prefs().getInt(KEY_BEST, 0)
        updateScoreUi()
    }

    private fun saveScore() {
        prefs().edit()
            .putInt(KEY_SCORE, score)
            .putInt(KEY_BEST, bestScore)
            .apply()
    }

    private fun updateScoreUi() {
        scoreText.text = "Pontos: $score   Recorde: $bestScore"
    }

    private fun endBattle(won: Boolean) {
        setMovesEnabled(false)
        battleLog.text = if (won) "Você venceu!" else "Seu dragão foi derrotado..."

        // Atualiza e salva a pontuação na hora (antes do atraso da tela final)
        val lostPoints = score
        if (won) {
            score += POINTS_PER_WIN
            if (score > bestScore) bestScore = score
        } else {
            score = 0
        }
        saveScore()
        updateScoreUi()

        // Espera um pouco para o jogador ver o último golpe
        handler.postDelayed({
            val player = playerDragon?.name ?: ""
            val enemy = enemyDragon?.name ?: ""

            if (won) {
                gameOverTitle.text = "Vitória!"
                gameOverTitle.setTextColor(0xFFFFD54F.toInt())
                gameOverMsg.text = "$player derrotou $enemy."
                gameOverScore.text =
                    "+$POINTS_PER_WIN pontos\nTotal: $score   Recorde: $bestScore"
            } else {
                gameOverTitle.text = "Game Over"
                gameOverTitle.setTextColor(0xFFEF5350.toInt())
                gameOverMsg.text = "$enemy derrotou $player."
                gameOverScore.text =
                    if (lostPoints > 0)
                        "Você perdeu $lostPoints pontos.\nPontuação zerada.   Recorde: $bestScore"
                    else
                        "Pontuação zerada.   Recorde: $bestScore"
            }
            btnRematch.text = "Lutar de novo contra $enemy"
            gameOverOverlay.visibility = View.VISIBLE
        }, 1200)
    }

    // Revanche: mesmo inimigo, vida cheia para os dois
    private fun restartBattle() {
        handler.removeCallbacksAndMessages(null)
        removeEnemy()
        battleStarted = false
        turnLocked = false

        gameOverOverlay.visibility = View.GONE
        startBattle()
    }

    private fun exitBattle() {
        handler.removeCallbacksAndMessages(null)
        removeEnemy()

        inBattle = false
        battleStarted = false
        turnLocked = false

        selectionOverlay.visibility = View.GONE
        battleHud.visibility = View.GONE
        enemyPanel.visibility = View.GONE
        btnBattle.visibility = View.VISIBLE
        placeButton?.visibility = View.VISIBLE
        overlay?.visibility = View.VISIBLE
        gameOverOverlay.visibility = View.GONE
        modelNode?.position = Position(0f, 0f, 0f)
        if (mode != Mode.AR) placeButton?.text = "Centralizar"
    }

    // Sai da batalha e recarrega o dragão na escala normal
    private fun leaveBattle() {
        exitBattle()
        loadModel()
    }

    private suspend fun buildEnemyNode(
        scene: SceneView,
        enemy: Dragon,
        x: Float
    ): ModelNode? {
        val location = modelLocation(enemy.glb) ?: return null
        val instance = scene.modelLoader.loadModelInstance(location) ?: return null
        return ModelNode(
            modelInstance = instance,
            scaleToUnits = modelScale(),
            centerOrigin = Position(0f, 0f, 0f)
        ).apply {
            position = Position(x = x)
            rotation = Rotation(y = 180f)
        }
    }

    private fun preloadEnemy() {
        val scene = sceneView ?: return
        val enemy = enemyDragon ?: return
        val expected = generation
        val x = if (mode == Mode.AR) 0.6f else 0.55f

        enemyLoadJob?.cancel()
        enemyLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val node = buildEnemyNode(scene, enemy, x) ?: return@launch
                if (expected != generation || sceneView !== scene) {
                    node.destroy()
                    return@launch
                }
                enemyNode = node   // pronto, mas ainda fora da cena
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(AUDIO_TAG, "Erro ao pré-carregar inimigo", e)
            }
        }
    }
}