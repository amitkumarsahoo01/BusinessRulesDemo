import { TestBed } from '@angular/core/testing';
import { ModelGraph } from '../../core/models/rules.models';
import { DmnStructure } from './dmn-structure';

const GRAPH: ModelGraph = {
  name: 'Shipping Cost',
  namespace: 'https://example.org/shipping',
  nodes: [
    { id: 'w', name: 'Weight', type: 'INPUT_DATA', typeRef: 'number', kind: null, hitPolicy: null, ruleCount: null, expressionText: null, description: null },
    { id: 'r', name: 'Rate Per Kg', type: 'BKM', typeRef: null, kind: 'FUNCTION', hitPolicy: null, ruleCount: null, expressionText: null, description: null },
    { id: 'c', name: 'Shipping Cost', type: 'DECISION', typeRef: 'number', kind: 'LITERAL_EXPRESSION', hitPolicy: null, ruleCount: null, expressionText: 'Weight * Rate Per Kg(Zone)', description: null },
    { id: 't', name: 'Tier', type: 'DECISION', typeRef: 'string', kind: 'DECISION_TABLE', hitPolicy: 'FIRST', ruleCount: 3, expressionText: null, description: null },
  ],
  edges: [
    { from: 'w', to: 'c', kind: 'INFORMATION' },
    { from: 'r', to: 'c', kind: 'KNOWLEDGE' },
    { from: 'c', to: 't', kind: 'INFORMATION' },
  ],
  itemDefinitions: [
    {
      name: 'tZone',
      typeRef: 'string',
      collection: false,
      allowedValues: '"EU","US"',
      components: [],
    },
    {
      name: 'tOrder',
      typeRef: null,
      collection: false,
      allowedValues: null,
      components: [{ name: 'weight', typeRef: 'number', collection: false, allowedValues: null, components: [] }],
    },
  ],
};

describe('DmnStructure', () => {
  async function create() {
    await TestBed.configureTestingModule({ imports: [DmnStructure] }).compileComponents();
    const fixture = TestBed.createComponent(DmnStructure);
    fixture.componentRef.setInput('graph', GRAPH);
    fixture.detectChanges();
    return fixture;
  }

  it('draws every element and edge and lists the data types', async () => {
    const fixture = await create();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('g.node').length).toBe(4);
    expect(el.querySelectorAll('path.edge').length).toBe(3);
    expect(el.querySelector('polygon')).toBeTruthy(); // the BKM
    expect(el.textContent).toContain('table · FIRST · 3 rows');
    expect(el.textContent).toContain('tZone');
    expect(el.textContent).toContain('weight');
  });

  it('shows details for the selected element, including its formula and neighbours', async () => {
    const fixture = await create();
    const el = fixture.nativeElement as HTMLElement;
    const cost = Array.from(el.querySelectorAll('g.node')).find((g) => g.textContent?.includes('Shipping Cost'))!;
    (cost as SVGElement).dispatchEvent(new Event('click'));
    fixture.detectChanges();

    const details = el.querySelector('.details')!;
    expect(details.textContent).toContain('Weight * Rate Per Kg(Zone)');
    expect(details.textContent).toContain('Weight');
    expect(details.textContent).toContain('Rate Per Kg');
    expect(details.textContent).toContain('Tier'); // used by
  });

  it('offers to open a decision table in the editor', async () => {
    const fixture = await create();
    const opened: string[] = [];
    fixture.componentInstance.openTable.subscribe((name) => opened.push(name));
    const el = fixture.nativeElement as HTMLElement;
    const tier = Array.from(el.querySelectorAll('g.node')).find((g) => g.textContent?.includes('Tier'))!;
    (tier as SVGElement).dispatchEvent(new Event('click'));
    fixture.detectChanges();

    (el.querySelector('.details button') as HTMLButtonElement).click();
    expect(opened).toEqual(['Tier']);
  });
});
