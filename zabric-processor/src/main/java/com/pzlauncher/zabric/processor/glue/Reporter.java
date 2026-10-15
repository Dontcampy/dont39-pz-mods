package com.pzlauncher.zabric.processor.glue;

/** A front end's diagnostics; {@code node} is the front end's element the message is about. */
public interface Reporter {
    void error(Object node, String message);

    void warning(Object node, String message);
}
