package io.github.burpmcp.extension;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class BridgePanel extends JPanel implements AutoCloseable {
    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final transient BridgeRuntime runtime;
    private final JLabel status = new JLabel();
    private final JLabel session = new JLabel();
    private final JLabel descriptor = new JLabel();
    private final JLabel error = new JLabel();
    private final JButton toggle = new JButton();
    private final JTextArea audit = new JTextArea(12, 80);
    private final Timer refreshTimer;

    BridgePanel(BridgeRuntime runtime) {
        super(new BorderLayout(8, 8));
        this.runtime = runtime;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel details = new JPanel(new GridLayout(0, 1, 4, 4));
        details.add(status);
        details.add(session);
        details.add(descriptor);
        details.add(error);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(toggle);
        controls.add(new JLabel("Disable stops the listener and queued work; a request already inside Burp may continue."));

        JPanel top = new JPanel(new BorderLayout());
        top.add(details, BorderLayout.CENTER);
        top.add(controls, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        audit.setEditable(false);
        audit.setLineWrap(false);
        add(new JScrollPane(audit), BorderLayout.CENTER);

        toggle.addActionListener(event -> {
            toggle.setEnabled(false);
            BridgeRuntime.Snapshot current = runtime.snapshot();
            if (current.enabled()) {
                runtime.disable();
            } else {
                runtime.enable();
            }
            refresh();
        });
        refreshTimer = new Timer(1000, event -> refresh());
        refreshTimer.start();
        refresh();
    }

    void refresh() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refresh);
            return;
        }
        BridgeRuntime.Snapshot snapshot = runtime.snapshot();
        status.setText(snapshot.enabled()
                ? "Status: enabled on 127.0.0.1:" + snapshot.port()
                : "Status: disabled");
        session.setText(snapshot.sessionPrefix() == null
                ? "Session: none"
                : "Session: " + snapshot.sessionPrefix() + "… (token hidden)");
        descriptor.setText("Discovery: " + snapshot.descriptorPath());
        error.setText(snapshot.lastError() == null ? " " : snapshot.lastError());
        toggle.setText(snapshot.enabled() ? "Disable bridge (kill switch)" : "Enable bridge");
        toggle.setEnabled(true);

        StringBuilder text = new StringBuilder("Recent metadata-only audit events\n");
        for (AuditLog.Event event : snapshot.recentAudit()) {
            text.append(TIME_FORMAT.format(event.time())).append("  ")
                    .append(event.operation()).append("  ")
                    .append(event.outcome()).append("  ")
                    .append(event.durationMs()).append("ms\n");
        }
        audit.setText(text.toString());
        audit.setCaretPosition(0);
    }

    @Override
    public void close() {
        refreshTimer.stop();
    }
}
