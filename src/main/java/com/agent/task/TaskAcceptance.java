package com.agent.task;

import com.agent.command.CommandResult;
import com.agent.command.CommandRunner;
import com.agent.session.Redactor;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 验收直接消费当前报告；退出码和模型的输出摘要都不足以证明测试通过。 */
public final class TaskAcceptance {
    /** 保护校验拒绝执行时 process 为 null，不伪造进程退出状态。 */
    public record Evidence(String kind, String verdict, String reason, String acceptanceVersion,
                           Map<String, String> files, CommandResult process, int tests,
                           Set<String> identities, Map<String, String> reports, boolean codingAllowed,
                           List<String> failures) {
        public String details() { return failures == null ? "" : String.join("\n", failures); }
    }

    private final Path root;
    private final TaskStore store;
    private final CommandRunner runner;

    public TaskAcceptance(Path root, TaskStore store, CommandRunner runner) {
        this.root = root;
        this.store = store;
        this.runner = runner;
    }

    public Evidence check(TaskRecord task, String kind, Set<String> expected)
            throws IOException, InterruptedException {
        String id = kind + "-" + java.util.UUID.randomUUID();
        Map<String, String> before = WorkspaceSnapshot.capture(root);
        List<String> changed = new TaskProtection(root, task.acceptanceFiles).changes(before);
        if (!changed.isEmpty()) {
            return recordEvidence(task, id, new Evidence(kind, "FAILED",
                    "验收输入版本已改变，未执行验收命令: " + String.join(", ", changed),
                    task.acceptanceVersion, before, null, 0, Set.of(), Map.of(), false, List.of()));
        }
        Path evidenceDir = store.evidenceDirectory(task.id, id);
        for (Path report : reports()) {
            Path archived = evidenceDir.resolve("before").resolve(root.relativize(report));
            Files.createDirectories(archived.getParent());
            Files.writeString(archived, Redactor.redact(Files.readString(report, StandardCharsets.UTF_8)).text(),
                    StandardCharsets.UTF_8);
            Files.delete(report);
        }
        task.checkpoint.reserveAcceptance();
        store.save(task);
        CommandResult process = runner.run(root, task.command, 60);
        Set<String> identities = new HashSet<>();
        Map<String, String> hashes = new java.util.TreeMap<>();
        String reason = "";
        int tests = 0;
        boolean usableReports = true;
        boolean failedTests = false;
        List<String> failures = new ArrayList<>();
        for (Path report : reports()) {
            byte[] bytes = Files.readAllBytes(report);
            byte[] persisted = Redactor.redact(new String(bytes, StandardCharsets.UTF_8)).text()
                    .getBytes(StandardCharsets.UTF_8);
            String relative = root.relativize(report).toString().replace('\\', '/');
            store.writeEvidenceFile(task.id, id, relative, persisted);
            hashes.put(relative, WorkspaceSnapshot.hash(persisted));
            try {
                ParsedReport parsed = parse(bytes, identities);
                tests += parsed.tests();
                usableReports &= !parsed.skipped();
                failedTests |= parsed.failed();
                failures.addAll(parsed.failures());
                if (!parsed.reason().isEmpty()) { reason = parsed.reason(); }
            } catch (InvalidReport invalid) {
                reason = invalid.getMessage();
                usableReports = false;
            }
        }
        if (reason.isEmpty() && process.timedOut()) { reason = "验收超时"; }
        if (reason.isEmpty() && !process.success()) { reason = "验收命令失败，退出码: " + process.exitCode(); }
        if (reason.isEmpty() && tests == 0) { reason = "验收没有实际执行的当前测试报告"; }
        if (reason.isEmpty() && !identities.containsAll(expected)) { reason = "验收缺少先前测试身份"; }
        boolean stableInputs = before.equals(WorkspaceSnapshot.capture(root));
        if (!stableInputs) { reason = "验收期间项目输入版本发生变化"; }
        boolean stableAgreement = new TaskProtection(root, task.acceptanceFiles).changes(before).isEmpty();
        if (!stableAgreement) {
            reason = "验收约定的输入版本已改变，请重新建立任务约定";
        }
        boolean codingAllowed = usableReports && tests > 0 && !process.timedOut()
                && (process.success() || failedTests) && stableInputs && stableAgreement;
        Evidence evidence = new Evidence(kind, reason.isEmpty() ? "PASSED" : "FAILED", reason,
                task.acceptanceVersion, before, process, tests, Set.copyOf(identities), hashes, codingAllowed,
                List.copyOf(failures));
        return recordEvidence(task, id, evidence);
    }

    private Evidence recordEvidence(TaskRecord task, String id, Evidence evidence) throws IOException {
        store.saveEvidence(task, id, evidence);
        task.files = evidence.files();
        task.checkpoint.acceptancePending = false;
        task.checkpoint.acceptanceResult = id;
        store.save(task);
        return evidence;
    }

    private List<Path> reports() throws IOException {
        Path directory = root.resolve("target/surefire-reports");
        if (!Files.exists(directory)) { return List.of(); }
        WorkspaceSnapshot.requireInside(root, directory);
        List<Path> reports = new ArrayList<>();
        // 只收集约定项目的默认产物位置，不能移动源码中同名的 XML 样本。
        try (var files = Files.list(directory)) {
            for (Path path : files.sorted().toList()) {
                if (path.getFileName().toString().matches("TEST-.*\\.xml")) {
                    WorkspaceSnapshot.requireInside(root, path);
                    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
                        throw new IOException("验收报告必须是项目内普通文件");
                    }
                    reports.add(path);
                }
            }
        }
        return reports;
    }

    private record ParsedReport(int tests, String reason, boolean failed, boolean skipped, List<String> failures) { }

    private ParsedReport parse(byte[] bytes, Set<String> identities) throws InvalidReport {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws SAXException {
                    throw error;
                }
            });
            Element suite = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            if (!suite.getTagName().equals("testsuite")) { throw new InvalidReport("验收报告格式不支持"); }
            var cases = suite.getElementsByTagName("testcase");
            int failures = 0, errors = 0, skipped = 0;
            List<String> details = new ArrayList<>();
            for (int index = 0; index < cases.getLength(); index++) {
                Element test = (Element) cases.item(index);
                String className = test.getAttribute("classname"), name = test.getAttribute("name");
                if (className.isBlank() || name.isBlank() || !identities.add(className + "#" + name)) {
                    throw new InvalidReport("验收测试身份缺失或重复");
                }
                failures += test.getElementsByTagName("failure").getLength();
                errors += test.getElementsByTagName("error").getLength();
                skipped += test.getElementsByTagName("skipped").getLength();
                for (String tag : List.of("failure", "error")) {
                    var problems = test.getElementsByTagName(tag);
                    for (int issue = 0; issue < problems.getLength(); issue++) {
                        Element problem = (Element) problems.item(issue);
                        details.add(className + "#" + name + " " + problem.getAttribute("type")
                                + ": " + problem.getAttribute("message") + "\n" + problem.getTextContent());
                    }
                }
            }
            if (count(suite, "tests") != cases.getLength() || count(suite, "failures") != failures
                    || count(suite, "errors") != errors || count(suite, "skipped") != skipped) {
                throw new InvalidReport("验收报告计数不一致");
            }
            return new ParsedReport(cases.getLength(), failures + errors + skipped > 0
                    ? "验收存在失败、错误或跳过的测试" : "", failures + errors > 0, skipped > 0, details);
        } catch (InvalidReport invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new InvalidReport("验收报告无法解析: " + invalid.getClass().getSimpleName());
        }
    }

    private int count(Element suite, String name) { return Integer.parseInt(suite.getAttribute(name)); }

    private static final class InvalidReport extends Exception {
        private InvalidReport(String message) { super(message); }
    }
}
