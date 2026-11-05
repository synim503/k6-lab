package ru.k6lab.core.script;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptOptionsStripperTest {

    @Test
    void optionsWithOnlyManagedKeysAreReplacedWithAComment() {
        var r = ScriptOptionsStripper.strip("import http from 'k6/http';\nexport const options = { vus: 10, duration: '1m' };\nexport default function () {}\n");
        assertThat(r.removed()).containsExactly("vus", "duration");
        assertThat(r.source()).doesNotContain("vus:").doesNotContain("duration").contains("заданы в админке")
                .contains("import http from 'k6/http';").contains("export default function () {}");
    }

    @Test
    void unmanagedOptionsStayAndNestedBracesAndStringsAreHandled() {
        String src = """
                export const options = {
                  insecureSkipTLSVerify: true,
                  // сценарии
                  scenarios: { a: { executor: 'constant-vus', vus: 1, duration: '1s', env: { X: '}' } } },
                  'thresholds': { http_req_duration: ['p(95)<500'] },
                  userAgent: "my}agent",
                  tags: { team: 'x, y' },
                };
                export default function () {}
                """;
        var r = ScriptOptionsStripper.strip(src);
        assertThat(r.removed()).containsExactly("scenarios", "thresholds", "tags");
        assertThat(r.source()).contains("insecureSkipTLSVerify: true").contains("userAgent: \"my}agent\"")
                .doesNotContain("scenarios").doesNotContain("thresholds").doesNotContain("team").contains("export default function () {}");
        // объект остался корректным: скобки сбалансированы, статические опции на месте
        assertThat(ScriptOptionsStripper.mask(r.source()).chars().filter(c -> c == '{').count())
                .isEqualTo(ScriptOptionsStripper.mask(r.source()).chars().filter(c -> c == '}').count());
    }

    @Test
    void scriptsWithoutManagedOptionsAreNotTouched() {
        for (String s : new String[]{
                "export default function () {}",
                "export const options = { insecureSkipTLSVerify: true };\nexport default function () {}",
                "export const options = getOptions();\nexport default function () {}",
                "// export const options = { vus: 1 };\nexport default function () {}",
                "const t = 'export const options = { vus: 1 }';\nexport default function () {}"}) {
            var r = ScriptOptionsStripper.strip(s);
            assertThat(r.changed()).as(s).isFalse();
            assertThat(r.source()).isEqualTo(s);
        }
    }

    @Test
    void typedAndLetDeclarationsAreSupported() {
        var r = ScriptOptionsStripper.strip("export let options: Options = { stages: [{ duration: '1s', target: 1 }], iterations: 5 }\nexport default function () {}");
        assertThat(r.removed()).containsExactly("stages", "iterations");
        assertThat(r.source()).doesNotContain("stages").contains("export default function () {}");
    }
}
