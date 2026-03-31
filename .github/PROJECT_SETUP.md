# GitHub Project Setup for RoverCtrl3

## Project Structure

**Project Name:** RoverCtrl3 Development  
**Description:** Project management for RoverCtrl3 development with automated workflows

## Columns

1. **Backlog** - New issues and features
2. **In Progress** - Currently being worked on
3. **Review** - Ready for code review/testing
4. **Done** - Completed and verified

## Automation Rules

### When issue is created:
- Add to "Backlog" column
- Apply appropriate labels

### When issue is assigned:
- Move to "In Progress" column

### When PR is created:
- Link to related issue
- Move issue to "Review" column

### When PR is merged:
- Move issue to "Done" column
- Close issue

## Labels

### Type:
- `bug` - Bug reports
- `enhancement` - New features
- `documentation` - Documentation updates
- `question` - Questions

### Priority:
- `priority: high` - Critical issues
- `priority: medium` - Important but not critical
- `priority: low` - Nice to have

### Area:
- `android` - Android app changes
- `firmware` - Firmware changes
- `hardware` - Hardware related
- `ci/cd` - CI/CD pipeline
- `ui` - User interface

## Milestones

### v2.6 (Next Release)
- OTA firmware updates
- Joystick sensitivity settings
- Basic video recording

### v2.7
- Raspberry Pi 5 integration
- Enhanced video recording
- Improved autopilot

### v2.8
- Advanced autopilot
- AI enhancements
- Performance optimizations

## Workflow

1. **Issue Creation** - Use templates for bugs/features
2. **Triaging** - Assign labels, priority, milestone
3. **Development** - Create feature branch, implement
4. **Review** - Code review, testing
5. **Merge** - Merge to develop, run CI
6. **Release** - Merge to main, create tag

## Team

- **Project Manager** - Oversees project progress
- **Android Developer** - Android app development
- **Firmware Developer** - ESP32 firmware
- **QA/Testing** - Testing and validation

## Metrics

- **Velocity** - Issues completed per week
- **Cycle Time** - Time from start to completion
- **Bug Rate** - Bugs per release
- **Test Coverage** - Code test coverage

## Integration with CI/CD

- Automated builds on push to main/develop
- Automated testing
- Release automation
- Deployment status

## How to Use

1. Create issues using templates
2. Assign to appropriate milestone
3. Move through columns as work progresses
4. Use labels for filtering
5. Review metrics regularly