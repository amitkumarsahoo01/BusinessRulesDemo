import { GraphEdge, GraphNode } from '../../core/models/rules.models';

export const NODE_WIDTH = 190;
export const NODE_HEIGHT = 60;
const GAP_X = 90;
const GAP_Y = 28;
const PAD = 24;

export interface PositionedNode {
  node: GraphNode;
  x: number;
  y: number;
}

export interface PositionedEdge {
  edge: GraphEdge;
  path: string;
}

export interface GraphLayout {
  nodes: PositionedNode[];
  edges: PositionedEdge[];
  width: number;
  height: number;
}

/**
 * Left-to-right layered layout of a DMN requirements graph: sources (inputs, BKMs) in column 0,
 * each element one column right of the deepest thing it requires, so data visibly flows into the
 * final decisions. Within a column, elements are ordered by the average row of what they depend
 * on to keep edges short. Cycles (invalid DMN, but possible in an import) are tolerated: depth
 * relaxation is capped, so the layout always terminates.
 */
export function layoutGraph(nodes: GraphNode[], edges: GraphEdge[]): GraphLayout {
  const ids = new Set(nodes.map((n) => n.id));
  const validEdges = edges.filter((e) => ids.has(e.from) && ids.has(e.to) && e.from !== e.to);

  const depth = new Map<string, number>(nodes.map((n) => [n.id, 0]));
  for (let pass = 0; pass < nodes.length; pass++) {
    let changed = false;
    for (const e of validEdges) {
      const wanted = (depth.get(e.from) ?? 0) + 1;
      if (wanted > (depth.get(e.to) ?? 0) && wanted < nodes.length) {
        depth.set(e.to, wanted);
        changed = true;
      }
    }
    if (!changed) {
      break;
    }
  }

  const columns: GraphNode[][] = [];
  for (const n of nodes) {
    const d = depth.get(n.id) ?? 0;
    (columns[d] ??= []).push(n);
  }

  const rowOf = new Map<string, number>();
  columns.forEach((column, d) => {
    if (!column) {
      return;
    }
    if (d > 0) {
      const average = (n: GraphNode): number => {
        const rows = validEdges.filter((e) => e.to === n.id && rowOf.has(e.from)).map((e) => rowOf.get(e.from)!);
        return rows.length ? rows.reduce((a, b) => a + b, 0) / rows.length : Number.MAX_SAFE_INTEGER;
      };
      column.sort((a, b) => average(a) - average(b));
    }
    column.forEach((n, i) => rowOf.set(n.id, i));
  });

  const tallest = Math.max(1, ...columns.map((c) => c?.length ?? 0));
  const columnHeight = (count: number): number => count * NODE_HEIGHT + (count - 1) * GAP_Y;
  const totalHeight = columnHeight(tallest);

  const positioned = new Map<string, PositionedNode>();
  columns.forEach((column, d) => {
    if (!column) {
      return;
    }
    const offset = (totalHeight - columnHeight(column.length)) / 2;
    column.forEach((node, i) => {
      positioned.set(node.id, {
        node,
        x: PAD + d * (NODE_WIDTH + GAP_X),
        y: PAD + offset + i * (NODE_HEIGHT + GAP_Y),
      });
    });
  });

  const positionedEdges: PositionedEdge[] = validEdges.map((edge) => {
    const from = positioned.get(edge.from)!;
    const to = positioned.get(edge.to)!;
    const x1 = from.x + NODE_WIDTH;
    const y1 = from.y + NODE_HEIGHT / 2;
    const x2 = to.x;
    const y2 = to.y + NODE_HEIGHT / 2;
    const bend = Math.max(30, (x2 - x1) / 2);
    return { edge, path: `M ${x1} ${y1} C ${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}` };
  });

  const usedColumns = columns.length;
  return {
    nodes: [...positioned.values()],
    edges: positionedEdges,
    width: PAD * 2 + usedColumns * NODE_WIDTH + Math.max(0, usedColumns - 1) * GAP_X,
    height: PAD * 2 + totalHeight,
  };
}
