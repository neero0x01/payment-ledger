import "@testing-library/jest-dom/vitest";

// jsdom has no layout, so the router's scroll restoration has nothing to scroll.
window.scrollTo = () => {};
