package com.ifpr.androidapptemplatear.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.ifpr.androidapptemplatear.R

class ChangeObjectFragment : Fragment() {

    private lateinit var sharedViewModel: SharedModelViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(
            R.layout.fragment_changeobject,
            container,
            false
        )
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        sharedViewModel =
            ViewModelProvider(requireActivity())[SharedModelViewModel::class.java]

        carregarModelos()
    }

    /**
     * Procura automaticamente todos os arquivos .glb
     * existentes na pasta assets.
     */
    private fun carregarModelos() {

        val container =
            view?.findViewById<LinearLayout>(R.id.categoryContainer)

        container?.removeAllViews()

        val modelos = requireContext()
            .assets
            .list("")
            ?.filter { arquivo ->
                arquivo.endsWith(".glb", ignoreCase = true)
            }
            ?.sorted()
            ?: emptyList()

        if (modelos.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "Nenhum modelo 3D encontrado.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        adicionarTitulo(container)

        modelos.forEach { arquivo ->
            adicionarModelo(container, arquivo)
        }
    }

    /**
     * Adiciona o título da lista de modelos.
     */
    private fun adicionarTitulo(container: LinearLayout?) {

        val titulo = TextView(requireContext()).apply {

            text = "Modelos 3D"

            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                20f
            )

            setTypeface(null, Typeface.BOLD)

            setTextColor(Color.BLACK)

            setPadding(
                0,
                24,
                0,
                16
            )
        }

        container?.addView(titulo)
    }

    /**
     * Cria um item da lista para cada modelo encontrado.
     */
    private fun adicionarModelo(
        container: LinearLayout?,
        arquivo: String
    ) {

        val itemLayout = LinearLayout(requireContext()).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.CENTER_VERTICAL

            setPadding(
                0,
                12,
                0,
                12
            )

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        /*
         * Remove a extensão .glb e deixa o nome
         * mais amigável para exibição.
         *
         * Exemplo:
         * pikachu.glb -> Pikachu
         * cadeira_escolar.glb -> Cadeira escolar
         */
        val nomeModelo = arquivo
            .removeSuffix(".glb")
            .removeSuffix(".GLB")
            .replace("_", " ")
            .replaceFirstChar {
                if (it.isLowerCase()) {
                    it.titlecase()
                } else {
                    it.toString()
                }
            }

        val nome = TextView(requireContext()).apply {

            text = nomeModelo

            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                17f
            )

            setTextColor(Color.DKGRAY)

            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val botao = Button(requireContext()).apply {

            text = "Exibir"

            setOnClickListener {

                /*
                 * Informa para a tela da câmera
                 * qual modelo deve ser carregado.
                 */
                sharedViewModel.selectModel(arquivo)

                /*
                 * Abre a tela de Realidade Aumentada.
                 */
                findNavController().navigate(
                    R.id.navigation_camera
                )
            }
        }

        itemLayout.addView(nome)
        itemLayout.addView(botao)

        container?.addView(itemLayout)

        adicionarDivisor(container)
    }

    /**
     * Adiciona uma linha separando os modelos.
     */
    private fun adicionarDivisor(
        container: LinearLayout?
    ) {

        val divisor = View(requireContext()).apply {

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply {

                topMargin = 4
                bottomMargin = 4
            }

            setBackgroundColor(Color.LTGRAY)
        }

        container?.addView(divisor)
    }
}