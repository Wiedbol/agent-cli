import com.agent.command.ProcessCommandRunner;
import java.nio.file.Files;
import java.nio.file.Path;

/** 在打包运行时确认超时会终止后代进程，避免只检查shell退出码。 */
public class PackagedProcessCheck {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Files.writeString(root.resolve("child.ps1"), "\uFEFF$p=Start-Process -FilePath ping.exe -ArgumentList '-n','30','127.0.0.1' -WindowStyle Hidden -PassThru\n"
                + "$p.Id | Set-Content child.pid\nWait-Process -Id $p.Id\n");
        var result = new ProcessCommandRunner(root).run(root,
                "powershell -NoProfile -ExecutionPolicy Bypass -File child.ps1", 3);
        if (!result.timedOut()) { throw new AssertionError("timeout not observed"); }
        long pid = Long.parseLong(Files.readString(root.resolve("child.pid")).strip().replace("\uFEFF", ""));
        if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) { throw new AssertionError("child survived"); }
        try (var files = Files.list(root)) {
            if (files.anyMatch(p -> p.getFileName().toString().startsWith("agent-command-"))) {
                throw new AssertionError("output handle not released");
            }
        }
        System.out.println("OFFLINE packaged timeout/process-tree check: PASS");
    }
}
