import { GraphEdge, GraphNode } from '../../core/models/rules.models';
import { NODE_HEIGHT, NODE_WIDTH, layoutGraph } from './graph-layout';

function node(id: string, type: GraphNode['type'] = 'DECISION'): GraphNode {
  return {
    id,
    name: id,
    type,
    typeRef: null,
    kind: null,
    hitPolicy: null,
    ruleCount: null,
    expressionText: null,
    description: null,
  };
}

describe('layoutGraph', () => {
  it('places each element one column right of what it requires', () => {
    const nodes = [node('in', 'INPUT_DATA'), node('a'), node('b')];
    const edges: GraphEdge[] = [
      { from: 'in', to: 'a', kind: 'INFORMATION' },
      { from: 'a', to: 'b', kind: 'INFORMATION' },
    ];
    const layout = layoutGraph(nodes, edges);
    const x = (id: string) => layout.nodes.find((n) => n.node.id === id)!.x;
    expect(x('in')).toBeLessThan(x('a'));
    expect(x('a')).toBeLessThan(x('b'));
    expect(layout.edges).toHaveLength(2);
    expect(layout.width).toBeGreaterThan(3 * NODE_WIDTH);
  });

  it('never overlaps elements in the same column', () => {
    const nodes = [node('i1', 'INPUT_DATA'), node('i2', 'INPUT_DATA'), node('i3', 'INPUT_DATA'), node('d')];
    const edges: GraphEdge[] = nodes.slice(0, 3).map((n) => ({ from: n.id, to: 'd', kind: 'INFORMATION' }));
    const layout = layoutGraph(nodes, edges);
    const ys = layout.nodes.filter((n) => n.node.type === 'INPUT_DATA').map((n) => n.y).sort((a, b) => a - b);
    expect(ys[1] - ys[0]).toBeGreaterThanOrEqual(NODE_HEIGHT);
    expect(ys[2] - ys[1]).toBeGreaterThanOrEqual(NODE_HEIGHT);
  });

  it('terminates on cyclic input and ignores edges to unknown elements', () => {
    const nodes = [node('a'), node('b')];
    const edges: GraphEdge[] = [
      { from: 'a', to: 'b', kind: 'INFORMATION' },
      { from: 'b', to: 'a', kind: 'INFORMATION' },
      { from: 'ghost', to: 'a', kind: 'INFORMATION' },
    ];
    const layout = layoutGraph(nodes, edges);
    expect(layout.nodes).toHaveLength(2);
    expect(layout.edges).toHaveLength(2);
  });

  it('handles an empty graph', () => {
    const layout = layoutGraph([], []);
    expect(layout.nodes).toHaveLength(0);
    expect(layout.width).toBeGreaterThan(0);
  });
});
