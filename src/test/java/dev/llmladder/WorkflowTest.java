package dev.llmladder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.llmladder.core.Principal;
import dev.llmladder.eval.Samples;
import dev.llmladder.fiscal.AccessKey;
import dev.llmladder.fiscal.NfeXmlParser;
import dev.llmladder.llm.LlmResponse;
import dev.llmladder.llm.Usage;
import dev.llmladder.workflow.RejectionCategory;
import dev.llmladder.workflow.WorkflowResult;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkflowTest {
    private static final Principal ACME = new Principal("ana", "acme", Set.of());

    @Test
    void validatesAccessKeyCheckDigit() {
        String valid = Samples.key(7).value();
        char last = valid.charAt(43);
        String broken = valid.substring(0, 43) + (last == '9' ? '0' : (char) (last + 1));

        assertThat(AccessKey.isValid(valid)).isTrue();
        assertThat(AccessKey.isValid(broken)).isFalse();
        assertThat(new AccessKey(valid).masked()).doesNotContain("11222333000181");
    }

    @Test
    void chainsStepsAndSuggestsActionWithRule() {
        String xml = Samples.xml(Samples.key(8), "204", "Rejeicao: Duplicidade de NF-e");

        WorkflowResult result = Platform.simulated().workflow.analyze(xml, ACME);

        assertThat(result.category()).isEqualTo(RejectionCategory.DUPLICIDADE);
        assertThat(result.suggestedAction()).contains("Consultar a chave");
        assertThat(result.rule()).isNotEmpty();
        assertThat(result.accessKey()).contains("...");
    }

    @Test
    void invalidModelOutputGoesToHumanInsteadOfBeingUsed() {
        Platform platform = Platform.with(request -> new LlmResponse(request.model(), "{\"category\":\"INVENTADA\",\"confidence\":1}",
                List.of(), LlmResponse.Stop.END_TURN, Usage.ZERO, null));

        WorkflowResult result = platform.workflow.analyze(Samples.xml(Samples.key(9), "204", "x"), ACME);

        assertThat(result.category()).isEqualTo(RejectionCategory.DESCONHECIDA);
        assertThat(result.needsHuman()).isTrue();
    }

    @Test
    void rejectsXxe() {
        String xxe = "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><infNFe Id=\"NFe&e;\"/>";

        assertThatThrownBy(() -> NfeXmlParser.parse(xxe)).isInstanceOf(NfeXmlParser.InvalidXmlException.class);
    }
}
