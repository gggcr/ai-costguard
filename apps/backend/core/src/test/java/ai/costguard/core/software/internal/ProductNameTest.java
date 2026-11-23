package ai.costguard.core.software.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Finding titles name a product once: the vendor is added only when the product's name does not start with it. */
class ProductNameTest {

    @Test
    void theVendorIsNotRepeated() {
        assertThat(SoftwareAuditRules.productName("Google", "Google Workspace")).isEqualTo("Google Workspace");
        assertThat(SoftwareAuditRules.productName("Яндекс", "Яндекс 360")).isEqualTo("Яндекс 360");
        assertThat(SoftwareAuditRules.productName("Zoom", "zoom")).isEqualTo("zoom");
    }

    @Test
    void aProductWithoutTheVendorGetsIt() {
        assertThat(SoftwareAuditRules.productName("Atlassian", "Jira")).isEqualTo("Atlassian Jira");
        assertThat(SoftwareAuditRules.productName("Salesforce", "Sales Cloud")).isEqualTo("Salesforce Sales Cloud");
        assertThat(SoftwareAuditRules.productName("Google", "Googleplex")).isEqualTo("Google Googleplex");
    }
}
