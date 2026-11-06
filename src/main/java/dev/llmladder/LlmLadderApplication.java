package dev.llmladder;

import dev.llmladder.core.Principal;
import dev.llmladder.eval.EvalCli;
import dev.llmladder.mcp.McpStdioServer;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Modos: sem argumentos sobe a API; "eval" roda o gate; "mcp" serve ferramentas de leitura por stdio. */
@SpringBootApplication
public class LlmLadderApplication {
    public static void main(String[] args) throws IOException {
        if (args.length > 0 && args[0].equals("eval")) {
            System.exit(EvalCli.run(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length > 0 && args[0].equals("mcp")) {
            Platform platform = Platform.simulated();
            String tenant = System.getenv().getOrDefault("LADDER_TENANT", "public");
            new McpStdioServer(platform.sefaz, platform.knowledge, new Principal("mcp", tenant, Set.of()))
                    .serve(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
            return;
        }
        SpringApplication.run(LlmLadderApplication.class, args);
    }
}
