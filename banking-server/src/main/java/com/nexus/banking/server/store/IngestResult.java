package com.nexus.banking.server.store;

import java.util.List;
import java.util.Map;

/** Outcome of a replicated batch: accepted ids are committed (or already present); rejected are permanent for this payload. */
public record IngestResult(List<String> accepted, Map<String, String> rejected, long checkpoint) {
}
