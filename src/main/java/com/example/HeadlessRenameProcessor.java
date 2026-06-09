package com.example;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.refactoring.rename.RenameProcessor;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.containers.MultiMap;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link RenameProcessor} that never blocks on UI.
 *
 * <p>An external agent driving refactorings over the plugin's HTTP API runs the IDE
 * unattended, so any modal dialog raised on the EDT hangs the operation forever. Stock
 * {@code RenameProcessor.run()} can raise two such dialogs for multi-file renames:
 * the usage <em>preview pane</em> and the <em>conflicts</em> ("Problems Detected")
 * dialog. This subclass suppresses both — equivalent to a user choosing "Continue" —
 * while still recording any conflicts so the caller can surface them rather than
 * silently discarding the safety signal.
 *
 * <p>Written in Java because the Java signatures of {@code isPreviewUsages} and
 * {@code showConflicts} use platform-nullability in array/generic positions that
 * Kotlin cannot denote when overriding.
 */
public class HeadlessRenameProcessor extends RenameProcessor {

    /** Conflicts IntelliJ reported (and which were proceeded past), for logging/inspection. */
    public final List<String> conflictMessages = new ArrayList<>();

    public HeadlessRenameProcessor(Project project,
                                   PsiElement element,
                                   String newName,
                                   boolean searchInComments,
                                   boolean searchTextOccurrences) {
        super(project, element, newName, searchInComments, searchTextOccurrences);
        setPreviewUsages(false);
    }

    @Override
    protected boolean isPreviewUsages(UsageInfo[] usages) {
        return false;
    }

    @Override
    protected boolean showConflicts(MultiMap<PsiElement, String> conflicts, UsageInfo[] usages) {
        if (conflicts != null && !conflicts.isEmpty()) {
            conflictMessages.addAll(conflicts.values());
        }
        return true; // proceed unattended instead of blocking on the modal dialog
    }
}
