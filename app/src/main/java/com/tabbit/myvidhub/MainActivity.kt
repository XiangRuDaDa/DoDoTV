package com.tabbit.myvidhub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tabbit.myvidhub.ui.LibraryViewModel
import com.tabbit.myvidhub.ui.phone.PhoneApp
import com.tabbit.myvidhub.ui.settings.forceTvMode
import com.tabbit.myvidhub.ui.theme.MyVidHubTheme
import com.tabbit.myvidhub.ui.theme.isTelevision
import com.tabbit.myvidhub.ui.tv.TvApp

/**
 * 唯一的 Activity 入口，只做一件事：判断这是不是电视，然后交给对应的一套实现。
 *
 * 手机端入口是 ui/phone/PhoneApp.kt，电视端入口是 ui/tv/TvApp.kt，
 * 两边各自拥有独立的导航结构、页面、组件与播放器，不通过 isTv 参数在同屏里分叉。
 * 这样改手机端界面不会牵连电视端，反之亦然。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyVidHubTheme {
                val context = LocalContext.current
                // 判定只发生在最外层一次：系统 uiMode 为主，设置里的强制开关为辅，
                // 并对部分国产盒子 uiMode 上报不准的情况做了无触摸屏兜底。
                val isTv = remember { context.isTelevision() || context.forceTvMode() }
                val vm: LibraryViewModel = viewModel()

                if (isTv) {
                    TvApp(vm = vm)
                } else {
                    PhoneApp(vm = vm)
                }
            }
        }
    }
}
