import org.gridkit.nanocloud.Cloud
import org.gridkit.nanocloud.CloudFactory
import org.gridkit.nanocloud.RemoteNode
import org.gridkit.nanocloud.VX
import java.io.Serializable

object OverSsh {
    @JvmStatic
    fun main(args: Array<String>) {
        CloudFactory.createCloud().run {
            node("my_remote_node").run {
                this.x(RemoteNode.REMOTE).useSimpleRemoting()
                this.x(RemoteNode.REMOTE).setHostsConfigFile("?na")
                this.x(RemoteNode.REMOTE).setRemoteAccount("root")
                this.x(RemoteNode.REMOTE).setRemoteHost("165.232.69.157")
                this.x(RemoteNode.REMOTE).setPassword("5ei8M92xY3GG5qT")

                this.x(VX.PROCESS).addJvmArg("-Dpassword=${System.getProperty("password")}")
                this.x(VX.PROCESS).addJvmArgs("-Xms64m", "-Xmx450m", "-Xverify:none",  "-Djava.awt.headless=true")

                exec(RemoteExecution())
            }
        }
    }

    class RemoteExecution: Serializable, Runnable{
        override fun run() {
            BestMoveOpt.main(emptyArray())
        }

    }
}