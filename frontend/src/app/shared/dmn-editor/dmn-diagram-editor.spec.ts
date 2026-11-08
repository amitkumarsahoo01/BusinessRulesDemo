import { Component, signal, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { DmnDiagramEditor } from './dmn-diagram-editor';
import { installFakeDmnEditor } from './fake-dmn-editor.testing';

@Component({
  imports: [DmnDiagramEditor],
  template: `<app-dmn-diagram-editor [xml]="xml()" [readOnly]="ro" (dirtyChange)="dirty = $event" />`,
})
class Host {
  readonly xml = signal('<definitions id="a"/>');
  ro = false;
  dirty = false;
  readonly editor = viewChild.required(DmnDiagramEditor);
}

const settle = () => new Promise((r) => setTimeout(r));

describe('DmnDiagramEditor', () => {
  let fake: ReturnType<typeof installFakeDmnEditor>;

  beforeEach(() => {
    fake = installFakeDmnEditor();
  });
  afterEach(() => fake.uninstall());

  it('opens the KIE editor with the given XML and reports dirty state', async () => {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    await settle();

    expect(fake.state.opened.length).toBe(1);
    expect(await fake.state.opened[0].initialContent).toBe('<definitions id="a"/>');
    expect(fake.state.opened[0].readOnly).toBe(false);

    fake.edit('<definitions id="b"/>');
    expect(fixture.componentInstance.dirty).toBe(true);
    expect(await fixture.componentInstance.editor().getContent()).toBe('<definitions id="b"/>');
  });

  it('swaps content in place when the xml input changes, and closes on destroy', async () => {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    await settle();

    fixture.componentInstance.xml.set('<definitions id="other"/>');
    fixture.detectChanges();
    await settle();
    expect(fake.state.opened.length).toBe(1);
    expect(fake.state.content).toBe('<definitions id="other"/>');
    expect(fake.state.saved).toBeGreaterThan(0);

    fixture.destroy();
    expect(fake.state.closed).toBe(1);
    expect(fake.listeners.length).toBe(0);
  });

  it('reload() resets edits even when the xml input did not change', async () => {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    await settle();

    fake.edit('<definitions id="edited"/>');
    fixture.componentInstance.editor().reload('<definitions id="a"/>');
    await settle();
    expect(fake.state.content).toBe('<definitions id="a"/>');
  });
});
