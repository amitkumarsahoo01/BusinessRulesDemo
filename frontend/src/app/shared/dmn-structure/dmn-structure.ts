import { Component, computed, input, output, signal } from '@angular/core';
import { GraphNode, ItemDefinition, ModelGraph } from '../../core/models/rules.models';
import { NODE_HEIGHT, NODE_WIDTH, PositionedNode, layoutGraph } from './graph-layout';

interface TypeRow {
  depth: number;
  name: string;
  typeRef: string | null;
  collection: boolean;
  allowedValues: string | null;
}

const TYPE_LABELS: Record<GraphNode['type'], string> = {
  INPUT_DATA: 'Input data',
  DECISION: 'Decision',
  BKM: 'Business knowledge (function)',
  KNOWLEDGE_SOURCE: 'Knowledge source',
};

/**
 * The model's structure as a DMN requirements diagram (inputs -> decisions), a details panel for
 * the selected element, and the model's data types. Read-only: editing stays in the table editor.
 */
@Component({
  selector: 'app-dmn-structure',
  templateUrl: './dmn-structure.html',
  styleUrl: './dmn-structure.scss',
})
export class DmnStructure {
  readonly graph = input.required<ModelGraph>();
  /** Emitted when the user asks to open a decision table in the editor. */
  readonly openTable = output<string>();

  readonly nodeWidth = NODE_WIDTH;
  readonly nodeHeight = NODE_HEIGHT;
  readonly selectedId = signal<string | null>(null);

  readonly layout = computed(() => layoutGraph(this.graph().nodes, this.graph().edges));

  readonly selected = computed(() => {
    const id = this.selectedId();
    return this.graph().nodes.find((n) => n.id === id) ?? null;
  });

  readonly requires = computed(() => this.neighbours('to', 'from'));
  readonly usedBy = computed(() => this.neighbours('from', 'to'));

  readonly typeRows = computed(() => {
    const rows: TypeRow[] = [];
    const walk = (items: ItemDefinition[], depth: number) => {
      for (const item of items) {
        rows.push({
          depth,
          name: item.name,
          typeRef: item.typeRef,
          collection: item.collection,
          allowedValues: item.allowedValues,
        });
        walk(item.components, depth + 1);
      }
    };
    walk(this.graph().itemDefinitions, 0);
    return rows;
  });

  readonly counts = computed(() => {
    const nodes = this.graph().nodes;
    return {
      inputs: nodes.filter((n) => n.type === 'INPUT_DATA').length,
      decisions: nodes.filter((n) => n.type === 'DECISION').length,
      knowledge: nodes.filter((n) => n.type === 'BKM' || n.type === 'KNOWLEDGE_SOURCE').length,
    };
  });

  typeLabel(node: GraphNode): string {
    return TYPE_LABELS[node.type];
  }

  select(node: GraphNode): void {
    this.selectedId.set(this.selectedId() === node.id ? null : node.id);
  }

  title(node: GraphNode): string {
    return node.name.length > 24 ? node.name.slice(0, 23) + '…' : node.name;
  }

  subtitle(node: GraphNode): string {
    switch (node.type) {
      case 'INPUT_DATA':
        return node.typeRef ?? 'input';
      case 'BKM':
        return 'function';
      case 'KNOWLEDGE_SOURCE':
        return 'knowledge source';
      default:
        if (node.kind === 'DECISION_TABLE') {
          return `table · ${node.hitPolicy ?? '?'} · ${node.ruleCount ?? 0} rows`;
        }
        return node.kind === 'LITERAL_EXPRESSION' ? 'formula' : 'decision';
    }
  }

  /** Corner-cut outline for business knowledge models (DMN notation). */
  bkmPoints(p: PositionedNode): string {
    const c = 14;
    const { x, y } = p;
    const w = NODE_WIDTH;
    const h = NODE_HEIGHT;
    return `${x + c},${y} ${x + w - c},${y} ${x + w},${y + c} ${x + w},${y + h - c} ${x + w - c},${y + h} ${x + c},${y + h} ${x},${y + h - c} ${x},${y + c}`;
  }

  private neighbours(match: 'from' | 'to', pick: 'from' | 'to'): GraphNode[] {
    const id = this.selectedId();
    if (!id) {
      return [];
    }
    const byId = new Map(this.graph().nodes.map((n) => [n.id, n]));
    return this.graph()
      .edges.filter((e) => e[match] === id)
      .map((e) => byId.get(e[pick]))
      .filter((n): n is GraphNode => !!n);
  }
}
