package edu.sustech.mobile.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App

/** A small in-app landing page for a SUSTech service hosted by its official portal. */
class ServicePortalFragment : Fragment(R.layout.fragment_service_portal) {

    private data class Link(
        val title: Int,
        val url: String? = null,
        val action: String? = null,
        val toolbarActionTitle: Int? = null,
        val toolbarActionUrl: String? = null,
    )

    private data class Portal(val intro: Int, val links: List<Link>)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val serviceId = requireArguments().getString(ARG_SERVICE).orEmpty()
        val chinese = resources.configuration.locales[0].language == "zh"
        val portal = portal(serviceId, chinese) ?: run {
            requireActivity().finish()
            return
        }

        // The exchange platform is the primary destination. Launch it as soon
        // as the user enters this service; its toolbar keeps the public project
        // introduction one tap away.
        if (serviceId == "exchange") {
            if (savedInstanceState == null) portal.links.firstOrNull()?.let(::open)
            return
        }

        view.findViewById<TextView>(R.id.portal_intro).text = getString(portal.intro)
        val actions = view.findViewById<LinearLayout>(R.id.portal_actions)
        portal.links.forEachIndexed { index, link ->
            val button = MaterialButton(requireContext()).apply {
                text = getString(link.title)
                isAllCaps = false
                setOnClickListener { open(link) }
            }
            val margin = (8 * resources.displayMetrics.density).toInt()
            actions.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { if (index > 0) topMargin = margin },
            )
        }
    }

    private fun open(link: Link) {
        if (link.action == ACTION_WIFI) {
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                App.toast(R.string.portal_no_handler)
            }
            return
        }
        link.url?.let {
            startActivity(
                ServicePortalActivity.intent(
                    requireContext(),
                    it,
                    link.toolbarActionUrl,
                    link.toolbarActionTitle,
                ),
            )
        }
    }

    private fun portal(id: String, chinese: Boolean): Portal? = when (id) {
        "booking" -> Portal(
            R.string.portal_booking_intro,
            listOf(Link(R.string.portal_booking_ehall, "https://ehall.sustech.edu.cn/new/index.html")),
        )
        "faculty" -> Portal(
            R.string.portal_faculty_intro,
            listOf(
                Link(
                    R.string.portal_faculty_open,
                    if (chinese) "https://www.sustech.edu.cn/zh/faculty/"
                    else "https://www.sustech.edu.cn/en/faculty/",
                ),
            ),
        )
        "exchange" -> Portal(
            R.string.portal_exchange_intro,
            listOf(
                Link(
                    R.string.portal_exchange_platform,
                    url = "https://ws.sustech.edu.cn/",
                    toolbarActionTitle = R.string.portal_exchange_overview,
                    toolbarActionUrl = if (chinese) {
                        "https://global.sustech.edu.cn/study/abroad_project"
                    } else {
                        "https://global.sustech.edu.cn/en/study/abroad_project"
                    },
                ),
            ),
        )
        "language_help" -> Portal(
            R.string.portal_language_help_intro,
            listOf(
                Link(
                    R.string.portal_language_help_booking,
                    "https://ehall.sustech.edu.cn/dxggyw/sys/yyzxyy/*default/index.do",
                ),
                Link(
                    R.string.portal_language_help_guide,
                    "https://cle.sustech.edu.cn/service.html?lang=${if (chinese) "zh-cn" else "en-us"}",
                ),
            ),
        )
        "wifi" -> Portal(
            R.string.portal_wifi_intro,
            listOf(Link(R.string.portal_wifi_settings, action = ACTION_WIFI)),
        )
        else -> null
    }

    companion object {
        private const val ARG_SERVICE = "portal_service"
        private const val ACTION_WIFI = "wifi_settings"

        fun newInstance(serviceId: String) = ServicePortalFragment().apply {
            arguments = Bundle().putStringCompat(ARG_SERVICE, serviceId)
        }

        private fun Bundle.putStringCompat(key: String, value: String): Bundle = apply {
            putString(key, value)
        }
    }
}
