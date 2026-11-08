import { DmnEditorApi, DmnEditorOpenArgs } from './dmn-editor-loader';

/** A stand-in for the KIE editor's window.DmnEditor global, so no 11 MB script is needed. */
export function installFakeDmnEditor() {
  const listeners: ((dirty: boolean) => void)[] = [];
  const state = { content: '', opened: [] as DmnEditorOpenArgs[], closed: 0, saved: 0 };
  const api: DmnEditorApi = {
    getContent: () => Promise.resolve(state.content),
    setContent: (_path, content) => {
      state.content = content;
      return Promise.resolve();
    },
    subscribeToContentChanges: (cb) => {
      listeners.push(cb);
      return cb;
    },
    unsubscribeToContentChanges: (cb) => listeners.splice(listeners.indexOf(cb), 1),
    markAsSaved: () => {
      state.saved++;
    },
    undo: () => undefined,
    redo: () => undefined,
    close: () => {
      state.closed++;
    },
  };
  (window as unknown as { DmnEditor: unknown }).DmnEditor = {
    open: (args: DmnEditorOpenArgs) => {
      state.opened.push(args);
      void args.initialContent.then((c) => (state.content = c));
      return api;
    },
  };
  return {
    state,
    /** Simulates the user editing the diagram. */
    edit(content: string) {
      state.content = content;
      listeners.forEach((l) => l(true));
    },
    listeners,
    uninstall: () => delete (window as unknown as { DmnEditor?: unknown }).DmnEditor,
  };
}
