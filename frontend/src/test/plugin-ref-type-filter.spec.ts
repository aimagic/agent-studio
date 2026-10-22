/**
 * 插件引用类型过滤：与 input-tree-select.getCompatibleTypes / 叶子禁用逻辑对齐。
 * 覆盖 bug 5142740。
 */

function getCompatibleTypes(type: string): string[] {
  const t = type?.toLowerCase();
  if (!t) {
    return [];
  }
  const fileSubTypes = ['default', 'doc', 'txt', 'excel', 'ppt', 'image', 'audio'];
  if (t === 'string') {
    return ['string', ...fileSubTypes.map(s => `file/${s}`)];
  }
  if (t === 'array<string>') {
    return ['array<string>', ...fileSubTypes.map(s => `array<file/${s}>`)];
  }
  if (t === 'file/default') {
    return fileSubTypes.map(s => `file/${s}`);
  }
  if (t === 'array<file/default>') {
    return fileSubTypes.map(s => `array<file/${s}>`);
  }
  return [t];
}

function setDifferentTypeDisabledEacl(list: any[], compatible: string[]): any[] {
  return list.map(item => {
    const hasChildren = !!item.children?.length;
    if (hasChildren) {
      item.disabled = false;
      item.children = setDifferentTypeDisabledEacl(item.children, compatible);
    } else {
      const itemType = String(item.type ?? '').toLowerCase();
      item.disabled = itemType.length > 0 && !compatible.includes(itemType);
    }
    return item;
  });
}

describe('plugin ref type filter helpers', () => {
  it('integer 入参应禁用 string 叶子，保留 object 可展开', () => {
    const compatible = getCompatibleTypes('integer');
    const tree = setDifferentTypeDisabledEacl(
      [
        {
          type: 'object',
          name: 'sys',
          children: [
            { type: 'string', name: 'user_id', key: 'u1' },
            { type: 'integer', name: 'dialogue_count', key: 'd1' },
          ],
        },
        { type: 'string', name: 'query', key: 'q1' },
      ],
      compatible,
    );
    expect(tree[0].disabled).toBe(false);
    expect(tree[0].children[0].disabled).toBe(true);
    expect(tree[0].children[1].disabled).toBe(false);
    expect(tree[1].disabled).toBe(true);
  });

  it('string 入参可兼容 file/image', () => {
    const compatible = getCompatibleTypes('string');
    expect(compatible).toContain('string');
    expect(compatible).toContain('file/image');
    const tree = setDifferentTypeDisabledEacl([{ type: 'file/image', name: 'img', key: 'i1' }], compatible);
    expect(tree[0].disabled).toBe(false);
  });
});
