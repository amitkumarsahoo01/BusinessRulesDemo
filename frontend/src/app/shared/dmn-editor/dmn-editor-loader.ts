/**
 * Loads Apache KIE's standalone DMN editor (@kie-tools/dmn-editor-standalone) on first use. The
 * bundle is ~11 MB and self-contained (its editor iframe is inlined), so it is copied as a static
 * asset by angular.json and fetched only when someone opens the DMN editor, never as part of the
 * app bundle. The path carries the package version so nginx can cache it as immutable.
 */
export const DMN_EDITOR_VERSION = '10.2.0';
export const DMN_EDITOR_SCRIPT = `vendor/kie-dmn-editor/${DMN_EDITOR_VERSION}/index.js`;

/** The subset of the editor's API (dist/index.d.ts) this app uses. */
export interface DmnEditorApi {
  getContent(): Promise<string>;
  setContent(path: string, content: string): Promise<void>;
  subscribeToContentChanges(callback: (isDirty: boolean) => void): (isDirty: boolean) => void;
  unsubscribeToContentChanges(callback: (isDirty: boolean) => void): void;
  markAsSaved(): void;
  undo(): Promise<void> | void;
  redo(): Promise<void> | void;
  close(): void;
}

export interface DmnEditorOpenArgs {
  container: Element;
  initialContent: Promise<string>;
  initialFileNormalizedPosixPathRelativeToTheWorkspaceRoot?: string;
  readOnly?: boolean;
  origin?: string;
  onError?: () => unknown;
}

export interface DmnEditorGlobal {
  open(args: DmnEditorOpenArgs): DmnEditorApi;
}

type EditorWindow = Window & { DmnEditor?: DmnEditorGlobal; DMNEditor?: DmnEditorGlobal };

let pending: Promise<DmnEditorGlobal> | null = null;

function loaded(): DmnEditorGlobal | undefined {
  const w = window as EditorWindow;
  return w.DmnEditor ?? w.DMNEditor;
}

export function loadDmnEditor(): Promise<DmnEditorGlobal> {
  const existing = loaded();
  if (existing) {
    return Promise.resolve(existing);
  }
  pending ??= new Promise<DmnEditorGlobal>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = new URL(DMN_EDITOR_SCRIPT, document.baseURI).toString();
    script.async = true;
    script.onload = () => {
      const api = loaded();
      if (api) {
        resolve(api);
      } else {
        pending = null;
        reject(new Error('The DMN editor script loaded but did not register itself.'));
      }
    };
    script.onerror = () => {
      pending = null;
      script.remove();
      reject(new Error('Could not download the DMN editor. Check your connection and try again.'));
    };
    document.head.appendChild(script);
  });
  return pending;
}
