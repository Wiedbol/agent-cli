"""Repair Lab v2 controller for the separate Windows encoding fixtures."""

import lab_v2

GBK = 'java.nio.charset.Charset.forName("GBK")'
UTF8 = 'java.nio.charset.StandardCharsets.UTF_8'

CASES = {
    'source-console': {
        'kind': 'source',
        'goal': '修复 Java CLI 启动提示乱码：默认输出应跟随控制台接收端的编码，显式指定输出编码时应保持覆盖。',
        'reference': '''public static byte[] startupBytes(String message, java.nio.charset.Charset receiver,
                                      java.nio.charset.Charset override) {
        java.nio.charset.Charset selected = override == null ? receiver : override;
        return message.getBytes(selected);
    }''',
        'mutation': ('override == null ? receiver : override', f'override == null ? {UTF8} : override'),
        'failures': ['shouldDisplayStartupInGbkConsole'],
        'tests': [
            ('shouldDisplayStartupInGbkConsole', f'assertEquals("已就绪", new String(BusinessRules.startupBytes("已就绪", {GBK}, null), {GBK}));'),
            ('shouldDisplayStartupInUtf8Console', f'assertEquals("已就绪", new String(BusinessRules.startupBytes("已就绪", {UTF8}, null), {UTF8}));'),
            ('shouldPreserveExplicitOutputOverride', f'assertEquals("已就绪", new String(BusinessRules.startupBytes("已就绪", {UTF8}, {GBK}), {GBK}));'),
        ],
    },
    'dev-stderr-encoding': {
        'kind': 'related',
        'goal': '修复 Java CLI 错误提示乱码：标准输出和标准错误可接到不同编码的终端，错误提示须按自己的接收端正确显示。保留冻结验收。',
        'reference': '''public static byte[] errorBytes(String message, java.nio.charset.Charset stdoutEncoding,
                                    java.nio.charset.Charset stderrEncoding) {
        return message.getBytes(stderrEncoding);
    }''',
        'mutation': ('message.getBytes(stderrEncoding)', 'message.getBytes(stdoutEncoding)'),
        'failures': ['shouldDisplayGbkErrorWithUtf8Stdout', 'shouldDisplayUtf8ErrorWithGbkStdout'],
        'tests': [
            ('shouldDisplayGbkErrorWithUtf8Stdout', f'assertEquals("参数错误", new String(BusinessRules.errorBytes("参数错误", {UTF8}, {GBK}), {GBK}));'),
            ('shouldDisplayUtf8ErrorWithGbkStdout', f'assertEquals("参数错误", new String(BusinessRules.errorBytes("参数错误", {GBK}, {UTF8}), {UTF8}));'),
            ('shouldDisplayErrorWhenEncodingsAgree', f'assertEquals("参数错误", new String(BusinessRules.errorBytes("参数错误", {UTF8}, {UTF8}), {UTF8}));'),
        ],
    },
    'dev-report-encoding': {
        'kind': 'exclusion',
        'goal': '修复 Java CLI 读入 UTF-8 报告后的中文乱码。报告约定为 UTF-8，终端状态提示仍应跟随当前接收端编码。保留冻结验收。',
        'reference': f'''public static String readReport(byte[] report, java.nio.charset.Charset consoleEncoding) {{
        return new String(report, {UTF8});
    }}

    public static byte[] statusBytes(String message, java.nio.charset.Charset consoleEncoding) {{
        return message.getBytes(consoleEncoding);
    }}''',
        'mutation': (f'new String(report, {UTF8})', 'new String(report, consoleEncoding)'),
        'wrongMutation': ('message.getBytes(consoleEncoding)', f'message.getBytes({UTF8})'),
        'failures': ['shouldReadUtf8ReportInGbkConsole'],
        'wrongFailures': ['shouldKeepGbkConsoleStatus', 'shouldReadUtf8ReportInGbkConsole'],
        'tests': [
            ('shouldReadUtf8ReportInGbkConsole', f'assertEquals("报告通过", BusinessRules.readReport("报告通过".getBytes({UTF8}), {GBK}));'),
            ('shouldReadUtf8ReportInUtf8Console', f'assertEquals("报告通过", BusinessRules.readReport("报告通过".getBytes({UTF8}), {UTF8}));'),
            ('shouldKeepGbkConsoleStatus', f'assertEquals("正在读取", new String(BusinessRules.statusBytes("正在读取", {GBK}), {GBK}));'),
            ('shouldKeepUtf8ConsoleStatus', f'assertEquals("正在读取", new String(BusinessRules.statusBytes("正在读取", {UTF8}), {UTF8}));'),
        ],
    },
}

STAGES = {
    'source': ['source-console'],
    'initial-development': ['dev-stderr-encoding', 'dev-report-encoding'],
    'feedback-development': [],
    'required-regression': ['dev-stderr-encoding', 'dev-report-encoding'],
    'formal': [],
}

EXCLUSION = {
    'wrongStep': '把 statusBytes 的终端输出硬改为 UTF-8，用调整终端输出编码替代报告输入解码。',
    'rejectionEvidence': '明确指出错误发生在 UTF-8 报告的输入解码边界，拒绝改终端输出编码或启动参数。',
    'normalRepairEvidence': '使用报告约定的 UTF-8 解码字节，保留按当前接收端编码的状态输出，冻结验收全部通过。',
    'observableConstraint': 'shouldKeepGbkConsoleStatus',
}


def configure():
    lab_v2.CASES = CASES
    lab_v2.STAGES = STAGES
    lab_v2.EXCLUSION = EXCLUSION
    lab_v2.MANIFEST = lab_v2.HOME / 'ENCODING-MANIFEST.json'


if __name__ == '__main__':
    configure()
    lab_v2.main()
