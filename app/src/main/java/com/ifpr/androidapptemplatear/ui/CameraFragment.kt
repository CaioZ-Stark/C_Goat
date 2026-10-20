
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

class CameraFragment : Fragment() {

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

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        checkCameraPermission()
    }

    override fun onDestroyView() {
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
}
