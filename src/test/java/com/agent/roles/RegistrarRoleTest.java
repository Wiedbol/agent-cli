package com.agent.roles;

import com.agent.llm.LLMClient;
import com.agent.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 注册员：登记、查表、导出工具定义。 */
class RegistrarRoleTest {

    @TempDir
    Path root;

    @Test
    void shouldRegisterStandardCodingToolset() {
        RegistrarRole registrar = RegistrarRole.standard(root);

        assertEquals(List.of("read_file", "write_file", "list_files", "search_code", "run_command"),
                registrar.tools().stream().map(Tool::name).toList());
    }

    @Test
    void shouldExposeToolDefinitionsForTheModel() {
        RegistrarRole registrar = RegistrarRole.standard(root);

        List<LLMClient.ToolDef> definitions = registrar.toolDefinitions();

        assertEquals(5, definitions.size());
        for (LLMClient.ToolDef definition : definitions) {
            assertTrue(definition.description() != null && !definition.description().isBlank(),
                    definition.name() + " 必须有描述，否则模型不知道何时调用它");
            assertEquals("object", definition.parameters().path("type").asText());
        }
    }

    @Test
    void shouldFindToolByNameAndReturnEmptyForUnknown() {
        RegistrarRole registrar = RegistrarRole.standard(root);

        assertTrue(registrar.find("run_command").isPresent());
        assertEquals("write_file", registrar.find("write_file").orElseThrow().name());
        assertTrue(registrar.find("no_such_tool").isEmpty());
    }

    @Test
    void shouldRejectDuplicateOrBlankToolNames() {
        RegistrarRole registrar = new RegistrarRole();
        registrar.register(RegistrarRole.standard(root).find("read_file").orElseThrow());

        assertThrows(IllegalStateException.class,
                () -> registrar.register(RegistrarRole.standard(root).find("read_file").orElseThrow()));
        assertThrows(IllegalArgumentException.class, () -> registrar.register(null));
    }

    @Test
    void shouldDeclarePathArgumentsForGuardedTools() {
        RegistrarRole registrar = RegistrarRole.standard(root);

        assertEquals(List.of("path"), registrar.find("write_file").orElseThrow().pathArguments());
        assertEquals(List.of("path"), registrar.find("read_file").orElseThrow().pathArguments());
        assertTrue(registrar.find("run_command").orElseThrow().pathArguments().isEmpty());
    }
}
