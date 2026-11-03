package com.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkillTrialResumeOptionsTest {
    @Test void shouldParseExplicitTrialTotalAndReviewNote() {
        var options = SkillCommands.parse(new String[]{"--resume-skill-trial", "trial-id", "--trial-control", "control",
                "--trial-max-steps", "8", "--review-note", "explicit allowance"});
        assertEquals(SkillCommands.Action.RESUME_TRIAL, options.action());
        assertEquals("trial-id", options.trialId());
        assertEquals(8, options.limit());
        assertEquals("explicit allowance", options.note());
    }
    @Test void shouldRejectMissingOrConflictingContinuationParameters() {
        for (String[] args : new String[][]{
                {"--resume-skill-trial", "id"},
                {"--resume-skill-trial", "id", "--trial-control", "a", "--trial-max-steps", "0", "--review-note", "note"},
                {"--resume-skill-trial", "id", "--trial-control", "a", "--trial-max-steps", "8", "--review-note", "note", "--yes"},
                {"--resume-skill-trial", "id", "--trial-control", "a", "--trial-max-steps", "8", "--review-note", "note", "--trial-source", "b"}}) {
            assertThrows(IllegalArgumentException.class, () -> SkillCommands.parse(args));
        }
    }
}
