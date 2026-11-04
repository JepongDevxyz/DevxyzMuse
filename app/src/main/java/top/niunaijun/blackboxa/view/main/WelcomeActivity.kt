package top.niunaijun.blackboxa.view.main

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.view.clones.ClonesActivity
import top.niunaijun.blackboxa.view.list.ListViewModel

class WelcomeActivity : AppCompatActivity() {

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        jump()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewInstalledAppList()
        jump()
    }

    private fun jump() {
        // Phase B (ported from the frozen Blacks-BlackBox fork): the
        // unified Clones screen is the home now. MainActivity (the
        // per-profile pager) stays reachable from the Clones menu.
        startActivity(Intent(this, ClonesActivity::class.java))
        finish()
    }

    private fun previewInstalledAppList(){
        val viewModel = ViewModelProvider(this,InjectionUtil.getListFactory()).get(ListViewModel::class.java)
        viewModel.previewInstalledList()
    }
}