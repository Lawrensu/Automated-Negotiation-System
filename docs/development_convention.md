# Development Conventions
This document serves to outline the conventions used for development.


## Philosophy
- **Be Deliberate**
	- Be intentional with everything that you do. Be very thoughtful and leave no gaps neither assumptions. 
	- Any gaps/assumptions would leave ambiguity. Be accurate and detailed.
- **Prioritize consistency over elegance**
	- The goal is to minimize uncertainty and familiarity. If unsure, review other parts of the codebase and keep the patterns used similar.
	- Clearly define patterns and follow them consistently. When exceptions are made out of necessity, make notes and document them clearly.
- **Separation of Concerns**
	- Every layer does one job. The database stores data. The API enforces business rules. The frontend renders UI. Never let the frontend talk directly to the database. Never put business logic in the database.
- **Less is more**
	- Minimize complexity, introduce additional complexity only when necessary. Strive for simplicity.
- **Modularise code and self-contained**
	- Manage complexity by modularizing code but avoid excessive and unnecessary abstraction.
- **Always document**
	- Tightly integrate documentation with codebase to keep the project self-contained. 
- **Documentation serves as working notes** 
	- Be verbose and capture as much information as possible without regard to tidiness. Recorded details can be tidied later, unrecorded details are lost forever (until rediscovered).


## Git Branching Approach
`main` for latest (production) stable checkpoint, `dev` for current active stable checkpoint.

Hybrid Branching approach for new implementations (`{task/description-member}`):

- **Definitions**:
	- Task: `{dev|feature|bugfix|docs}`	
	- Description: `react-implementation`, `feature-regression`, etc.
	- Member: Project member working on the task

- Primary feature branch is `{task/description}` 
- Individual developers create their personal branches `{task/description-member}` from the feature branch as their workspace, merge upstream into `{task/description}`

## Git Commit Approach
- Commit conventions:
	- Single-line commit: `file.ext: description`
	- Multi-line commit format: [see below](#multi-line-commit-format)
- Use whitespace to organize code cleanly (to be refined during development)
	- 3 whitespace rows between functions
	- 5 whitespace rows between sections of code that have different functionality
- Code comments should complement the code by focusing on explaining the whys

### Multi-line Commit Format

Example:
```md
Summary description of commit:

- `file.ext`: Description of changes
- `file.ext`: Description of changes
...
```


## Coding Conventions
- Make sure to alwasy `git fetch` and `git pull` to make sure your branch is following the latest remote branch (Watch out for merge conflict). 
- Use tabs with size 4 by default (recommended to configure VSCode project settings to use tabs).
- Use single quotes `'example'` by default, with double quotes only for docstrings