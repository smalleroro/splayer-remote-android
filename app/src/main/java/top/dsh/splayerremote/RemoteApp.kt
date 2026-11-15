package top.dsh.splayerremote

import android.app.Application

/**
 * 应用级容器。
 *
 * 项目只有三千行，引 Hilt 是负收益；这里用一个极简的定位器把
 * 「唯一的一份会话」挂到进程上，让 Activity 与前台服务拿到同一个实例。
 *
 * 关键约束：**轮询只允许存在于 SessionRepository 一处**。
 * 之前 ViewModel 和 Service 各建一个 SPlayerApi、各自轮询，
 * 造成重复请求、行为分叉、改地址不同步三个问题。
 */
class RemoteApp : Application() {

    lateinit var session: SessionRepository
        private set

    override fun onCreate() {
        super.onCreate()
        session = SessionRepository(applicationContext)
    }
}
