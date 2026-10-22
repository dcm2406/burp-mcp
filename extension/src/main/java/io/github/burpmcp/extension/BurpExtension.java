package io.github.burpmcp.extension;

import burp.api.montoya.MontoyaApi;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;

public final class BurpExtension implements burp.api.montoya.BurpExtension {
    private BridgeRuntime runtime;
    private BridgePanel panel;

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Burp MCP");

        ObjectMapper mapper = new ObjectMapper();
        Path descriptorPath = SessionDescriptorStore.resolvePath(System.getenv());
        runtime = new BridgeRuntime(new MontoyaBurpGateway(api), mapper, descriptorPath);
        panel = new BridgePanel(runtime);

        api.userInterface().applyThemeToComponent(panel);
        api.userInterface().registerSuiteTab("Burp MCP", panel);
        api.extension().registerUnloadingHandler(this::unload);

        runtime.enable();
        panel.refresh();
    }

    private void unload() {
        if (panel != null) {
            panel.close();
        }
        if (runtime != null) {
            runtime.close();
        }
    }
}
