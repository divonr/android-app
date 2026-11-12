import { describe, expect, it } from 'vitest'
import { getIntegrationTitle, getToolName } from '../utils/toolCatalog'

describe('toolCatalog: the code sandbox', () => {
  it('is one tool under its stored id and under the name the models call it by', () => {
    // The enabled-tools settings and every stored tool call keep the id python_interpreter
    expect(getToolName('python_interpreter')).toBe('Workspace Sandbox')
    expect(getToolName('workspace_sandbox')).toBe('Workspace Sandbox')
    expect(getIntegrationTitle('python_interpreter')).toBe('Built-in Tools')
    expect(getIntegrationTitle('workspace_sandbox')).toBe('Built-in Tools')
  })

  it('leaves the other tools as they were', () => {
    expect(getToolName('get_date_time')).toBe('Get Date and Time')
    expect(getToolName('not_a_tool')).toBe('not_a_tool')
    expect(getIntegrationTitle('github_read_file')).toBe('GitHub')
  })
})
