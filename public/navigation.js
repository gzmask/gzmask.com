(() => {
  const scrollYKey = "gzmaskScrollY";
  const archiveScrollYKey = "gzmaskArchiveScrollY";
  let archiveScrollY = 0;
  let pendingScrollY = null;
  let scrollSaveScheduled = false;

  // This app changes routes with Datastar morphs, so the browser cannot restore
  // a different document's scroll position automatically.
  history.scrollRestoration = "manual";

  function currentState() {
    return history.state && typeof history.state === "object" ? history.state : {};
  }

  function updateCurrentEntry(patch) {
    history.replaceState({ ...currentState(), ...patch }, "", location.href);
  }

  function saveScrollPosition() {
    updateCurrentEntry({ [scrollYKey]: window.scrollY });
  }

  function scheduleScrollSave() {
    if (scrollSaveScheduled) return;
    scrollSaveScheduled = true;
    requestAnimationFrame(() => {
      scrollSaveScheduled = false;
      saveScrollPosition();
    });
  }

  function restoreAfterNextFetch(scrollY) {
    pendingScrollY = Number.isFinite(scrollY) ? Math.max(0, scrollY) : 0;
  }

  window.archiveNavigation = {
    preparePostNavigation() {
      archiveScrollY = window.scrollY;
      updateCurrentEntry({ [scrollYKey]: archiveScrollY });
      restoreAfterNextFetch(0);
    },

    postHistoryState() {
      return {
        [scrollYKey]: 0,
        [archiveScrollYKey]: archiveScrollY,
      };
    },

    backToArchive() {
      const savedScrollY = currentState()[archiveScrollYKey];
      if (!Number.isFinite(savedScrollY)) return false;
      history.back();
      return true;
    },

    restoreAfterNextFetch,

    handlePopState() {
      restoreAfterNextFetch(currentState()[scrollYKey] ?? 0);
    },
  };

  window.addEventListener("scroll", scheduleScrollSave, { passive: true });

  document.addEventListener("datastar-fetch", (event) => {
    if (event.detail?.type !== "finished" || pendingScrollY === null) return;

    const targetScrollY = pendingScrollY;
    pendingScrollY = null;
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        window.scrollTo(0, targetScrollY);
        saveScrollPosition();
      });
    });
  });

  const initialScrollY = currentState()[scrollYKey];
  if (Number.isFinite(initialScrollY)) {
    requestAnimationFrame(() => window.scrollTo(0, initialScrollY));
  } else {
    saveScrollPosition();
  }
})();
