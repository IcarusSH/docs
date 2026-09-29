'use strict';

/**
 * Settings user page controller.
 */
angular.module('docs').controller('SettingsUser', function($scope, $state, $uibModal, Restangular, $dialog, $translate) {
  /**
   * Load users from server.
   */
  $scope.loadUsers = function() {
    Restangular.one('user/list').get({
      sort_column: 1,
      asc: true
    }).then(function(data) {
      $scope.users = data.users;
    });
  };

  $scope.loadUsers();

  /**
   * Edit a user.
   */
  $scope.editUser = function(user) {
    $state.go('settings.user.edit', { username: user.username });
  };

  /**
   * Open the CSV import modal. User picks a file; we upload it as multipart/form-data
   * and surface a summary of created / updated / skipped / errors.
   */
  $scope.openImport = function() {
    $uibModal.open({
      templateUrl: 'partial/docs/settings.user.import.html',
      controller: 'SettingsUserImport'
    }).result.then(function() {
      // Reload the user list after a successful import
      $scope.loadUsers();
    }, angular.noop);
  };
});