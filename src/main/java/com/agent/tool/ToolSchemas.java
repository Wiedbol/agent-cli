package com.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * JSON Schema 构造器：避免每个工具手写一遍 {@code {"type":"object","properties":{...}}}。
 *
 * <p>Schema 是「模型能不能正确调用工具」的第一道关卡：参数名、类型、必填项写错，
 * 模型就会频繁传错参数，最终表现为「工具老是失败」。
 */
public final class ToolSchemas {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolSchemas() {
    }

    /** 共享的 ObjectMapper（Jackson 的 ObjectMapper 是线程安全的，构造一次即可）。 */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** 一个参数声明。 */
    public record Param(String name, String type, String description, boolean required) {

        public Param {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }

        public static Param required(String name, String type, String description) {
            return new Param(name, type, description, true);
        }

        public static Param optional(String name, String type, String description) {
            return new Param(name, type, description, false);
        }
    }

    /** 生成 {@code {"type":"object","properties":{...},"required":[...]}}。 */
    public static JsonNode object(Param... params) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode properties = root.putObject("properties");
        ArrayNode required = root.putArray("required");
        for (Param param : params) {
            ObjectNode property = properties.putObject(param.name());
            property.put("type", param.type());
            property.put("description", param.description());
            if (param.required()) {
                required.add(param.name());
            }
        }
        return root;
    }
}
