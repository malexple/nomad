package org.nomad.mailbox;

public record AppendResult(long seq, boolean duplicate) {}
