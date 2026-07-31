package dev.pam.nativeapp.render

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment

/** Native lifecycle/controller boundary for one retained PAM route. */
internal class PamRouteFragment : Fragment() {
    private var routeView: View? = null

    fun bind(view: View) {
        routeView = view
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = routeView ?: FrameLayout(requireContext())

    override fun onDestroyView() {
        super.onDestroyView()
        routeView = null
    }
}
