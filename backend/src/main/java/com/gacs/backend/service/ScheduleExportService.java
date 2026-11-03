package com.gacs.backend.service;

import com.gacs.backend.exception.ScheduleExportRejectedException;
import com.gacs.backend.model.OptimizationPlan;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Serializes a stored schedule only after the shared execution gate accepts it. */
@Service
public class ScheduleExportService {

    public enum Format {
        CSV("csv", new MediaType("text", "csv", StandardCharsets.UTF_8)),
        JSON("json", MediaType.APPLICATION_JSON);

        private final String extension;
        private final MediaType contentType;

        Format(String extension, MediaType contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }

        public String extension() {
            return extension;
        }

        public MediaType contentType() {
            return contentType;
        }
    }

    /** A file ready to send: its download name, content type and UTF-8 bytes. */
    public record ExportFile(String filename, MediaType contentType, byte[] content) {}

    private final ScheduleGate gate;
    private final ApprovedScheduleCsvSerializer csv;
    private final ApprovedScheduleJsonSerializer json;

    public ScheduleExportService(ScheduleGate gate, ApprovedScheduleCsvSerializer csv, ApprovedScheduleJsonSerializer json) {
        this.gate = gate;
        this.csv = csv;
        this.json = json;
    }

    @Transactional(noRollbackFor = ScheduleExportRejectedException.class)
    public ExportFile export(UUID runId, Format format, String email) {
        OptimizationPlan plan = gate.owned(runId, email);
        var checked = gate.check(plan, email, true);
        String body = format == Format.CSV ? csv.serialize(checked.schedule())
            : json.serialize(checked.schedule(), plan.getRun(), checked.approval());
        return new ExportFile("schedule-run-" + runId + "." + format.extension(), format.contentType(),
            body.getBytes(StandardCharsets.UTF_8));
    }
}
