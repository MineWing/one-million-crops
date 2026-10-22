package com.onemillioncrops.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GuiServiceTest {
    @Test
    void nextPageOnlyExistsBeforeTheLastPage() {
        assertFalse(GuiService.hasNextPage(0, 0));
        assertFalse(GuiService.hasNextPage(0, 1));
        assertTrue(GuiService.hasNextPage(0, 100));
        assertFalse(GuiService.hasNextPage(99, 100));
    }

    @Test
    void manyRefreshRequestsCauseOneRebuild() {
        GuiService.RefreshCoalescer refresh = new GuiService.RefreshCoalescer();
        assertFalse(refresh.takeStale());

        refresh.markStale();
        refresh.markStale();
        refresh.markStale();

        assertTrue(refresh.takeStale());
        assertFalse(refresh.takeStale());
    }
}
