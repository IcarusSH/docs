'use strict';

/**
 * Document modal borrow controller.
 *
 * Lets a user request temporary access to a document they only have SEARCHABLE on.
 * Resolves the approver via the same /acl/target/search typeahead used by AclEdit,
 * and POSTs /borrow/request.
 */
angular.module('docs').controller('DocumentModalBorrow', function($scope, $uibModalInstance, $q, Restangular) {

  // Sensible defaults: 7 days, READ-only
  $scope.durationDays = 7;
  $scope.perm = 'READ';
  $scope.approverUsername = '';
  $scope.note = '';
  $scope.error = null;

  /**
   * Typeahead source for the approver field. Reuses /acl/target/search which
   * returns both users and groups; we keep only users (groups can't approve borrows).
   */
  $scope.getApproverTypeahead = function($viewValue) {
    var deferred = $q.defer();
    Restangular.one('acl/target/search').get({ search: $viewValue }).then(function(data) {
      var users = _.map(data.users || [], function(u) {
        u.username = u.username;
        return u;
      });
      deferred.resolve(users);
    }, function() {
      deferred.resolve([]);
    });
    return deferred.promise;
  };

  /**
   * Submit the request. On validation/server errors, surface the message to the
   * modal body instead of closing it.
   */
  $scope.submit = function() {
    $scope.error = null;
    if (!$scope.approverUsername) {
      $scope.error = 'Approver is required';
      return;
    }
    if (!$scope.perm) {
      $scope.error = 'Permission is required';
      return;
    }
    if (!$scope.durationDays || $scope.durationDays < 1 || $scope.durationDays > 30) {
      $scope.error = 'Duration must be 1..30 days';
      return;
    }

    var payload = {
      document_id: $scope.$resolve.document ? $scope.$resolve.document.id : null,
      approver_username: $scope.approverUsername,
      perm: $scope.perm,
      duration_days: $scope.durationDays,
      note: $scope.note
    };

    Restangular.one('borrow/request').post('', payload).then(function() {
      $uibModalInstance.close();
    }, function(response) {
        var msg = (response && response.data && response.data.message) || 'Borrow request failed';
        // Translate the server error key if it's a translate id; otherwise show raw text.
        $scope.error = msg;
    });
  };

  $scope.cancel = function() {
    $uibModalInstance.dismiss();
  };
});